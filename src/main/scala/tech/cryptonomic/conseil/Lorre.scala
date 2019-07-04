package tech.cryptonomic.conseil

import akka.actor.ActorSystem
import akka.Done
import akka.stream.scaladsl.Source
import akka.stream.ActorMaterializer
import mouse.any._
import com.typesafe.scalalogging.LazyLogging
import tech.cryptonomic.conseil.tezos.{
  ApiOperations,
  FeeOperations,
  ShutdownComplete,
  TezosErrors,
  TezosNodeInterface,
  TezosNodeOperator,
  TezosTypes
}
import tech.cryptonomic.conseil.tezos.repositories.SlickRepositories
import tech.cryptonomic.conseil.tezos.TezosTypes.{
  Account,
  AccountId,
  BlockReference,
  BlockTagged,
  Delegate,
  PublicKeyHash
}
import tech.cryptonomic.conseil.io.MainOutputs.LorreOutput
import tech.cryptonomic.conseil.util.DatabaseUtil
import tech.cryptonomic.conseil.config.{Custom, Everything, LorreAppConfig, Newest}
import tech.cryptonomic.conseil.config.Platforms

import scala.concurrent.duration._
import scala.annotation.tailrec
import scala.concurrent.{Await, Future}
import scala.concurrent.duration.Duration
import scala.util.{Failure, Success, Try}
import tech.cryptonomic.conseil.tezos.TezosDatastore

/**
  * Entry point for synchronizing data between the Tezos blockchain and the Conseil database.
  */
object Lorre extends App with TezosErrors with LazyLogging with LorreAppConfig with LorreOutput {

  //reads all configuration upstart, will only complete if all values are found

  val config = loadApplicationConfiguration(args)

  //stop if conf is not available
  config.left.foreach { _ =>
    sys.exit(1)
  }

  //unsafe call, will only be reached if loadedConf is a Right, otherwise the merge will fail
  val LorreAppConfig.CombinedConfiguration(
    lorreConf,
    tezosConf,
    callsConf,
    streamingClientConf,
    batchingConf,
    verbose
  ) = config.merge
  val ignoreProcessFailures = sys.env.get(LORRE_FAILURE_IGNORE_VAR)

  //the dispatcher is visible for all async operations in the following code
  implicit val system: ActorSystem = ActorSystem("lorre-system")
  implicit val dispatcher = system.dispatcher

  //how long to wait for graceful shutdown of system components
  val shutdownWait = 10.seconds

  //whatever happens we try to clean up
  sys.addShutdownHook(shutdown())

  lazy val db = DatabaseUtil.db

  //bring slick-based repositories in scope
  val repos = new SlickRepositories
  import repos._

  //define all datastore services
  val tezosFees = new FeeOperations
  val tezosStore = new TezosDatastore
  val apis = new ApiOperations

  val tezosNodeOperator = new TezosNodeOperator(
    new TezosNodeInterface(tezosConf, callsConf, streamingClientConf),
    tezosConf.network,
    batchingConf,
    apis
  )

  /** close resources for application stop */
  private[this] def shutdown(): Unit = {
    logger.info("Doing clean-up")
    db.close()
    val nodeShutdown =
      tezosNodeOperator.node
        .shutdown()
        .flatMap((_: ShutdownComplete) => system.terminate())

    Await.result(nodeShutdown, shutdownWait)
    logger.info("All things closed")
  }

  /** Tries to fetch blocks head to verify if connection with Tezos node was successfully established */
  @tailrec
  private[this] def checkTezosConnection(): Unit =
    Try {
      Await.result(tezosNodeOperator.getBareBlockHead(), lorreConf.bootupConnectionCheckTimeout)
    } match {
      case Failure(e) =>
        logger.error("Could not make initial connection to Tezos", e)
        Thread.sleep(lorreConf.bootupRetryInterval.toMillis)
        checkTezosConnection()
      case Success(_) =>
        logger.info("Successfully made initial connection to Tezos")
    }

  /** The regular loop, once connection with the node is established */
  @tailrec
  private[this] def mainLoop(iteration: Int): Unit = {
    val noOp = Future.successful(())
    val processing = for {
      _ <- processTezosBlocks()
      _ <- if (iteration % lorreConf.feeUpdateInterval == 0) processFees()
      else noOp
    } yield ()

    /* Won't stop Lorre on failure from processing the chain, unless overridden by the environment to halt.
     * Can be used to investigate issues on consistently failing block or account processing.
     * Otherwise, any error will make Lorre proceed as expected by default (stop or wait for next cycle)
     */
    val attemptedProcessing =
      if (ignoreProcessFailures.exists(ignore => ignore == "true" || ignore == "yes"))
        processing.recover {
          //swallow the error and proceed with the default behaviour
          case f @ (AccountsProcessingFailed(_, _) | BlocksProcessingFailed(_, _) | DelegatesProcessingFailed(_, _)) =>
            logger.error("Failed processing but will keep on going next cycle", f)
        } else
        processing

    Await.result(attemptedProcessing, atMost = Duration.Inf)

    lorreConf.depth match {
      case Newest =>
        logger.info("Taking a nap")
        Thread.sleep(lorreConf.sleepInterval.toMillis)
        mainLoop(iteration + 1)
      case _ =>
        logger.info("Synchronization is done")
    }
  }

  displayInfo(tezosConf)
  if (verbose.on) displayConfiguration(Platforms.Tezos, tezosConf, (LORRE_FAILURE_IGNORE_VAR, ignoreProcessFailures))

  try {
    checkTezosConnection()
    mainLoop(0)
  } finally {
    shutdown()
  }

  private[this] def processFees(): Future[Done] = {
    logger.info("Processing latest Tezos fee data...")
    val computedFees = tezosFees.processTezosAverageFees(lorreConf.numberOfFeesAveraged)
    db.run(computedFees)
      .andThen {
        case Success(Some(written)) => logger.info("Wrote {} average fees to the database.", written)
        case Success(None) => logger.info("Wrote average fees to the database.")
        case Failure(e) => logger.error("Could not write average fees to the database because", e)
      }
      .map(_ => Done)
  }

  /**
    * Fetches all blocks not in the database from the Tezos network and adds them to the database.
    * Additionally stores account references that needs updating, too
    */
  private[this] def processTezosBlocks(): Future[Done] = {
    import cats.instances.future._
    import cats.syntax.apply._
    import TezosTypes.Syntax._

    logger.info("Processing Tezos Blocks..")

    val blockPagesToSynchronize = lorreConf.depth match {
      case Newest => tezosNodeOperator.getBlocksNotInDatabase()
      case Everything => tezosNodeOperator.getLatestBlocks()
      case Custom(n) => tezosNodeOperator.getLatestBlocks(Some(n), lorreConf.headHash)
    }

    /* will store a single page of block results */
    def processBlocksPage(
        results: tezosNodeOperator.BlockFetchingResults
    )(implicit mat: ActorMaterializer): Future[Int] = {
      def logBlockOutcome: PartialFunction[Try[(Option[Int], Option[Int])], Unit] = {
        case Success((blocksCount, accountsCount)) =>
          logger.info(
            "Wrote {} blocks to the database, checkpoint stored for{} account updates",
            blocksCount.getOrElse(results.size),
            accountsCount.fold("")(" " + _)
          )
        case Failure(e) =>
          logger.error("Could not write blocks or accounts checkpoints to the database.", e)
      }

      def logVotingOutcome[A]: PartialFunction[Try[Option[A]], Unit] = {
        case Success(votesCount) =>
          logger.info("Wrote{} voting data records to the database", votesCount.fold("")(" " + _))
        case Failure(e) =>
          logger.error("Could not write voting data to the database", e)
      }

      //ignore the account ids for storage, and prepare the checkpoint account data
      //we do this on a single sweep over the list, pairing the results and then unzipping the outcome
      val (blocks, accountUpdates) =
        results.map {
          case (block, accountIds) =>
            block -> accountIds.taggedWithBlock(block.data.hash, block.data.header.level)
        }.toList.unzip

      for {
        _ <- db.run(tezosStore.storeBlocksAndCheckpointAccounts(blocks, accountUpdates)) andThen logBlockOutcome
        _ <- processVotesForBlocks(results.map { case (block, _) => block }) andThen logVotingOutcome
        _ <- processAccountsForBlocks(accountUpdates) // should this fail, we still recover data from the checkpoint
      } yield results.size

    }

    blockPagesToSynchronize.flatMap {
      // Fails the whole process if any page processing fails
      case (pages, total) => {
        //this will be used for the pages streaming, and within all sub-processing doing a similar paging trick
        implicit val mat = ActorMaterializer()

        //custom progress tracking for blocks
        val logProgress =
          logProcessingProgress(entityName = "block", totalToProcess = total, processStartNanos = System.nanoTime()) _

        // Process each page on his own, and keep track of the progress
        Source
          .fromIterator(() => pages)
          .mapAsync[tezosNodeOperator.BlockFetchingResults](1)(identity)
          .mapAsync(1) {
            processBlocksPage(_) <* processTezosAccountsCheckpoint <* processTezosDelegates()
          }
          .runFold(0) { (processed, justDone) =>
            processed + justDone <| logProgress
          }

      }
    } transform {
      case Failure(accountFailure @ AccountsProcessingFailed(_, _)) =>
        Failure(accountFailure)
      case Failure(delegateFailure @ DelegatesProcessingFailed(_, _)) =>
        Failure(delegateFailure)
      case Failure(e) =>
        val error = "Could not fetch blocks from client"
        logger.error(error, e)
        Failure(BlocksProcessingFailed(message = error, e))
      case Success(_) => Success(Done)
    }

  }

  /**
    * Fetches voting data for the blocks and stores any relevant
    * result into the appropriate database table
    */
  def processVotesForBlocks(blocks: List[TezosTypes.Block]): Future[Option[Int]] =
    tezosNodeOperator.getVotingDetails(blocks).flatMap {
      case (proposals, bakersBlocks, ballotsBlocks) =>
        db.run(
          tezosStore.storeBlocksVotingDetails(proposals, bakersBlocks, ballotsBlocks)
        )
    }

  /* Fetches accounts from account-id and saves those associated with the latest operations
   * (i.e.the highest block level)
   */
  private[this] def processAccountsForBlocks(
      updates: List[BlockTagged[List[AccountId]]]
  )(implicit mat: ActorMaterializer): Future[Done] = {
    logger.info("Processing latest Tezos data for updated accounts...")

    def keepLatestAccountIds(associations: List[(AccountId, BlockReference)]): Map[AccountId, BlockReference] =
      associations.foldLeft(Map.empty[AccountId, BlockReference]) { (collected, entry) =>
        val key = entry._1
        if (collected.contains(key)) collected else collected + (key -> entry._2)
      }

    val sorted = updates.flatMap {
      case BlockTagged(hash, level, ids) =>
        ids.map(_ -> (hash, level))
    }.sortBy {
      case (id, (hash, level)) => level
    }(Ordering[Int].reverse)

    val toBeFetched = keepLatestAccountIds(sorted)

    process(toBeFetched)
  }

  /** Fetches and stores all accounts from the latest blocks still in the checkpoint */
  private[this] def processTezosAccountsCheckpoint(implicit mat: ActorMaterializer): Future[Done] = {
    logger.info("Selecting all accounts touched in the checkpoint table, this might take a while...")
    db.run(tezosStore.fetchLatestAccountsFromCheckpoint) flatMap { checkpoints =>
      if (checkpoints.nonEmpty) {
        logger.info(
          "I loaded all of {} checkpointed ids from the DB and will proceed to fetch updated accounts information from the chain",
          checkpoints.size
        )
        process(checkpoints)
      } else {
        logger.info("No data to fetch from the accounts checkpoint")
        Future.successful(Done)
      }
    }
  }

  /* Fetches the data from the chain node and stores accounts into the data store.
   * @param ids a pre-filtered map of account ids with latest block referring to them
   */
  private[this] def process(ids: Map[AccountId, BlockReference])(implicit mat: ActorMaterializer): Future[Done] = {
    import cats.Monoid
    import cats.instances.list._
    import cats.instances.int._
    import cats.instances.option._
    import cats.instances.tuple._
    import cats.syntax.functorFilter._
    import cats.syntax.monoid._

    def logWriteFailure: PartialFunction[Try[_], Unit] = {
      case Failure(e) =>
        logger.error("Could not write accounts to the database")
    }

    def logOutcome: PartialFunction[Try[(Int, Option[Int])], Unit] = {
      case Success((accountsRows, delegateCheckpointRows)) =>
        logger.info(
          "{} accounts were touched on the database. Checkpoint stored for{} delegates.",
          accountsRows,
          delegateCheckpointRows.fold("")(" " + _)
        )
    }

    def processAccountsPage(
        taggedAccounts: Seq[BlockTagged[Map[AccountId, Account]]]
    ): Future[(Int, Option[Int])] = {
      import TezosTypes.Syntax._
      val taggedList = taggedAccounts.toList
      val delegatesCheckpoint = taggedList.map {
        case BlockTagged(blockHash, blockLevel, accountsMap) =>
          val delegateKeys = accountsMap.values.toList
            .mapFilter(_.delegate.value)

          delegateKeys.taggedWithBlock(blockHash, blockLevel)
      }
      db.run(tezosStore.storeAccountsAndCheckpointDelegates(taggedList, delegatesCheckpoint))
        .andThen(logWriteFailure)
    }

    logger.info("Ready to fetch updated accounts information from the chain")
    val (pages, total) = tezosNodeOperator.getAccountsForBlocks(ids)

    /* Streams the (unevaluated) incoming data, actually fetching the results.
     * We use combinators to keep the ongoing requests' flow under control, taking advantage of
     * akka-streams automatic backpressure control.
     * The results are grouped to optimize for database storage.
     * We do this to re-aggregate results from pages which are now based on single blocks,
     * which would lead to inefficient storage performances as-is.
     */
    val saveAccounts = Source
        .fromIterator(() => pages)
        .mapAsync[List[BlockTagged[Map[AccountId, Account]]]](1)(identity)
        .mapConcat(identity)
        .grouped(batchingConf.blockPageSize)
        .mapAsync(1)(processAccountsPage)
        .runFold(Monoid[(Int, Option[Int])].empty) { (processed, justDone) =>
          processed |+| justDone
        } andThen logOutcome

    saveAccounts.andThen {
      //additional cleanup, that can fail with no downsides
      case Success(checkpoints) =>
        val processed = Some(ids.keySet)
        logger.info("Cleaning processed accounts from the checkpoint...")
        Await.result(
          db.run(tezosStore.removeAccountsFromCheckpoint(processed)),
          atMost = batchingConf.accountPageProcessingTimeout
        )
        logger.info("Done cleaning checkpointed accounts.")
      case _ =>
        ()
    }.transform {
      case Failure(e) =>
        val error = "I failed to fetch accounts from client and update them"
        logger.error(error, e)
        Failure(AccountsProcessingFailed(message = error, e))
      case success => Success(Done)
    }
  }

  /** Fetches and stores all delegates from the latest blocks stored in the database. */
  private[this] def processTezosDelegates(): Future[Done] = {
    import cats.Monoid
    import cats.instances.int._
    import cats.instances.future._
    import cats.syntax.flatMap._
    import cats.syntax.monoid._

    logger.info("Processing latest Tezos data for account delegates...")

    def logOutcome: PartialFunction[Try[Int], Unit] = {
      case Success(rows) =>
        logger.info("{} delegates were touched on the database.", rows)
    }

    def logWriteFailure: PartialFunction[Try[_], Unit] = {
      case Failure(e) =>
        logger.error("Could not write delegates to the database")
    }

    def processDelegatesPage(
        taggedDelegates: Seq[BlockTagged[Map[PublicKeyHash, Delegate]]]
    ): Future[Int] =
      db.run(tezosStore.storeDelegatesAndCopyContracts(taggedDelegates.toList))
        .andThen(logWriteFailure)

    val saveDelegates = db.run(tezosStore.fetchLatestDelegatesFromCheckpoint) flatTap { checkpoints =>
          implicit val mat = ActorMaterializer()

          logger.info(
            "I loaded all stored delegate references and will proceed to fetch updated information from the chain"
          )
          val (pages, total) = tezosNodeOperator.getDelegatesForBlocks(checkpoints)

          /* Streams the (unevaluated) incoming data, actually fetching the results.
           * We use combinators to keep the ongoing requests' flow under control, taking advantage of
           * akka-streams automatic backpressure control.
           * The results are grouped to optimize for database storage.
           * We do this to re-aggregate results from pages which are now based on single blocks,
           * which would lead to inefficient storage performances as-is.
           */
          Source
            .fromIterator(() => pages)
            .mapAsync[List[BlockTagged[Map[PublicKeyHash, Delegate]]]](1)(identity)
            .mapConcat(identity)
            .grouped(batchingConf.blockPageSize)
            .mapAsync(1)(processDelegatesPage)
            .runFold(Monoid[Int].empty) { (processed, justDone) =>
              processed |+| justDone
            } andThen logOutcome
        }

    saveDelegates.andThen {
      //additional cleanup, that can fail with no downsides
      case Success(checkpoints) =>
        val processed = Some(checkpoints.keySet)
        logger.info("Cleaning checkpointed delegates..")
        Await.result(
          db.run(tezosStore.removeDelegatesFromCheckpoint(processed)),
          atMost = batchingConf.delegatePageProcessingTimeout
        )
        logger.info("Done cleaning checkpointed delegates.")
      case _ =>
        ()
    }.transform {
      case Failure(e) =>
        val error = "I failed to fetch delegates from client and update them"
        logger.error(error, e)
        Failure(DelegatesProcessingFailed(message = error, e))
      case success => Success(Done)
    }

  }

  /** Keeps track of time passed between different partial checkpoints of some entity processing
    * Designed to be partially applied to set properties of the whole process once, and then only compute partial completion
    *
    * @param entityName a string that will be logged to identify what kind of resource is being processed
    * @param totalToProcess how many entities there were in the first place
    * @param processStartNanos a nano-time from jvm monotonic time, used to identify when the whole processing operation began
    * @param processed how many entities were processed at the current checkpoint
    */
  private[this] def logProcessingProgress(entityName: String, totalToProcess: Int, processStartNanos: Long)(
      processed: Int
  ): Unit = {
    val elapsed = System.nanoTime() - processStartNanos
    val progress = processed.toDouble / totalToProcess
    logger.info("Completed processing {}% of total requested {}s", "%.2f".format(progress * 100), entityName)

    val etaMins = Duration(scala.math.ceil(elapsed / progress) - elapsed, NANOSECONDS).toMinutes
    if (processed < totalToProcess && etaMins > 1) logger.info("Estimated time to finish is around {} minutes", etaMins)
  }

}
