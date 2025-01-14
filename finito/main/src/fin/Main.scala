package fin

import scala.concurrent.duration._

import cats.effect._
import cats.effect.std.{Dispatcher, Env}
import cats.implicits._
import com.comcast.ip4s._
import doobie._
import org.http4s.client.Client
import org.http4s.ember.client.EmberClientBuilder
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.ci._
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import zio.Runtime

import fin.config._
import fin.persistence._

object Main extends IOApp {

  implicit val zioRuntime: zio.Runtime[zio.Clock with zio.Console] =
    Runtime.default.withEnvironment(
      zio.ZEnvironment[zio.Clock, zio.Console](
        zio.Clock.ClockLive,
        zio.Console.ConsoleLive
      )
    )
  implicit val logger: Logger[IO] = Slf4jLogger.getLogger

  val env = Env[IO]

  def run(arg: List[String]): IO[ExitCode] = {
    val server = serviceResources(env).use { serviceResources =>
      implicit val dispatcherEv = serviceResources.dispatcher
      val config                = serviceResources.config
      val timer                 = Temporal[IO]

      for {
        _ <- FlywaySetup.init[IO](
          serviceResources.databaseUri,
          config.databaseUser,
          config.databasePassword
        )
        _ <- logger.info(
          show"Starting finito server version ${BuildInfo.version}"
        )
        _           <- logger.debug("Creating services...")
        services    <- Services[IO](serviceResources)
        _           <- logger.debug("Bootstrapping caliban...")
        interpreter <- CalibanSetup.interpreter[IO](services)

        logLevel <- env.get("LOG_LEVEL")
        debug = logLevel.exists(CIString(_) === ci"DEBUG")
        refresherIO = (timer.sleep(1.minute) >> Routes.keepFresh[IO](
          serviceResources.client,
          timer,
          config.port,
          config.host
        )).background.useForever
        _ <- logger.debug("Starting http4s server...")
        port <- IO.fromOption(Port.fromInt(config.port))(
          new Exception(show"Invalid value for port: '${config.port}'")
        )
        host <- IO.fromOption(Host.fromString(config.host))(
          new Exception(show"Invalid value for host: '${config.host}'")
        )
        _ <-
          EmberServerBuilder
            .default[IO]
            .withPort(port)
            .withHost(host)
            .withHttpApp(Routes.routes[IO](interpreter, debug))
            .build
            .use(_ => IO.never)
            .both(refresherIO)
      } yield ()
    }
    server.as(ExitCode.Success)
  }

  private def serviceResources(
      env: Env[IO]
  ): Resource[IO, ServiceResources[IO]] =
    for {
      client <- EmberClientBuilder.default[IO].build
      config <- Resource.eval(FinitoFiles.config[IO](env))
      dbPath <- Resource.eval(FinitoFiles.databasePath[IO](env))
      _      <- Resource.eval(FinitoFiles.backupPath[IO](dbPath))
      dbUri = FinitoFiles.databaseUri(dbPath)
      transactor <- TransactorSetup.sqliteTransactor[IO](dbUri)
      dispatcher <- Dispatcher.parallel[IO]
    } yield ServiceResources(client, config, transactor, dispatcher, dbUri)
}

object Banner {
  val value: String = """
 _________________
< Server started! >
 -----------------
\                             .       .
 \                           / `.   .' " 
  \                  .---.  <    > <    >  .---.
   \                 |    \  \ - ~ ~ - /  /    |
         _____          ..-~             ~-..-~
        |     |   \~~~\.'                    `./~~~/
       ---------   \__/                        \__/
      .'  O    \     /               /       \  " 
     (_____,    `._.'               |         }  \/~~~/
      `----.          /       }     |        /    \__/
            `-.      |       /      |       /      `. ,~~|
                ~-.__|      /_ - ~ ^|      /- _      `..-'   
                     |     /        |     /     ~-.     `-. _  _  _
                     |_____|        |_____|         ~ - . _ _ _ _ _>
"""
}

final case class ServiceResources[F[_]](
    client: Client[F],
    config: ServiceConfig,
    transactor: Transactor[F],
    dispatcher: Dispatcher[F],
    databaseUri: String
)
