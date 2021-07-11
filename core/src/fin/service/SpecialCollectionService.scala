package fin.service

import scala.util.Try

import cats.effect.Sync
import cats.implicits._
import io.chrisdavenport.log4cats.Logger
import javax.script._
import org.luaj.vm2.LuaBoolean

import fin.Types._

import ProcessResult._

class SpecialCollectionService[F[_]: Sync: Logger](
    wrappedService: CollectionService[F],
    collectionHooks: List[CollectionHook],
    scriptEngineManager: ScriptEngineManager
) extends CollectionService[F] {

  override def collections: F[List[Collection]] =
    wrappedService.collections

  override def createCollection(
      args: MutationsCreateCollectionArgs
  ): F[Collection] = wrappedService.createCollection(args)

  override def collection(
      args: QueriesCollectionArgs
  ): F[Collection] = wrappedService.collection(args)

  override def deleteCollection(
      args: MutationsDeleteCollectionArgs
  ): F[Unit] = wrappedService.deleteCollection(args)

  override def changeCollectionName(
      args: MutationsChangeCollectionNameArgs
  ): F[Collection] = wrappedService.changeCollectionName(args)

  override def addBookToCollection(
      args: MutationsAddBookArgs
  ): F[Collection] =
    for {
      resp   <- wrappedService.addBookToCollection(args)
      engine <- Sync[F].delay(scriptEngineManager.getEngineByName("luaj"))
      _ <-
        collectionHooks
          .filter { hook =>
            hook.`type` == HookType.Add && hook.collection != args.collection
          }
          .traverse(hook => {
            for {
              bindings     <- bindings(args.collection, args.book)
              hookResponse <- processHook(hook, engine, bindings)
              _ <- hookResponse.traverse {
                case Add    => addHookCollection(hook, args.book)
                case Remove => removeHookCollection(hook, args.book)
              }
            } yield ()
          })
    } yield resp

  override def removeBookFromCollection(
      args: MutationsRemoveBookArgs
  ): F[Unit] = wrappedService.removeBookFromCollection(args)

  private def addHookCollection(hook: CollectionHook, book: Book): F[Unit] = {
    wrappedService
      .addBookToCollection(
        MutationsAddBookArgs(hook.collection, book)
      )
      .void
      .handleErrorWith(err =>
        Logger[F].error(
          show"""
               |Unable to add book to special collection '${hook.collection}',
               |reason: ${err.getMessage}""".stripMargin.replace("\n", " ")
        )
      )
  }

  private def removeHookCollection(
      hook: CollectionHook,
      book: Book
  ): F[Unit] = {
    wrappedService
      .removeBookFromCollection(
        MutationsRemoveBookArgs(hook.collection, book.isbn)
      )
      .void
      .handleErrorWith(err =>
        Logger[F].error(
          show"""
               |Unable to remove book from special collection
               |'${hook.collection}', reason: ${err.getMessage}""".stripMargin
            .replace("\n", " ")
        )
      )
  }

  private def bindings(collection: String, book: Book): F[Bindings] = {
    for {
      bindings <- Sync[F].delay(new SimpleBindings)
      _        <- Sync[F].delay(bindings.put("collection", collection))
      _        <- Sync[F].delay(bindings.put("title", book.title))
      _        <- Sync[F].delay(bindings.put("authors", book.authors))
      _        <- Sync[F].delay(bindings.put("isbn", book.isbn))
    } yield bindings
  }

  private def processHook(
      hook: CollectionHook,
      engine: ScriptEngine,
      bindings: Bindings
  ): F[Option[ProcessResult]] =
    for {
      _      <- Sync[F].delay(engine.eval(hook.code, bindings))
      addStr <- Sync[F].delay(bindings.get("add"))
      rmStr  <- Sync[F].delay(bindings.get("remove"))
      maybeAdd = Try(
        Option(addStr.asInstanceOf[LuaBoolean])
      ).toOption.flatten.map(_.booleanValue)
      maybeRemove = Try(
        Option(rmStr.asInstanceOf[LuaBoolean])
      ).toOption.flatten.map(_.booleanValue)
    } yield maybeAdd
      .collect { case true => Add }
      .orElse(maybeRemove.collect { case true => Remove })
}

object SpecialCollectionService {
  def apply[F[_]: Sync: Logger](
      wrappedService: CollectionService[F],
      collectionHooks: List[CollectionHook],
      scriptEngineManager: ScriptEngineManager
  ) =
    new SpecialCollectionService[F](
      wrappedService,
      collectionHooks,
      scriptEngineManager
    )
}

sealed trait ProcessResult

object ProcessResult {
  case object Add    extends ProcessResult
  case object Remove extends ProcessResult
}
