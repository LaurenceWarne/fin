package fin

import caliban.CalibanError.ExecutionError
import caliban._
import caliban.interop.cats.implicits._
import caliban.wrappers.ApolloTracing.apolloTracing
import caliban.wrappers.Wrappers
import cats.effect.Effect
import cats.implicits._

import fin.Operations._
import fin.service.book._
import fin.service.collection._

import CalibanError._
import ResponseValue._
import Value._

object CalibanSetup {

  def interpreter[F[_]: Effect](
      bookInfoService: BookInfoService[F],
      bookManagementService: BookManagementService[F],
      collectionService: CollectionService[F]
  )(implicit
      runtime: zio.Runtime[zio.clock.Clock with zio.console.Console]
  ): F[GraphQLInterpreter[Any, CalibanError]] = {
    val queries = Queries[F](
      booksArgs => bookInfoService.search(booksArgs),
      bookArgs => bookInfoService.fromIsbn(bookArgs),
      collectionService.collections,
      collectionArgs => collectionService.collection(collectionArgs),
      _ => ???
    )
    val mutations = Mutations[F](
      args => collectionService.createCollection(args),
      args => collectionService.deleteCollection(args).map(_ => None),
      args => collectionService.updateCollection(args),
      args => collectionService.addBookToCollection(args),
      args => collectionService.removeBookFromCollection(args).map(_ => None),
      args => bookManagementService.startReading(args),
      args => bookManagementService.finishReading(args),
      args => bookManagementService.rateBook(args),
      args => bookManagementService.createBook(args),
      args => bookManagementService.deleteBookData(args).map(_ => None),
      _ => ???
    )
    val api = GraphQL.graphQL(RootResolver(queries, mutations))
    (api @@ apolloTracing @@ Wrappers.printErrors)
      .interpreterAsync[F]
      .map(_.provide(runtime.environment))
      .map(withErrors(_))
  }

  // 'Effect failure' is from this line:
  // https://github.com/ghostdogpr/caliban/blob/2e4d6ec571ca15a1b66f6e4f8a0ef0c94c80513d/core/src/main/scala/caliban/execution/Executor.scala#L224
  private def withErrors[R](
      interpreter: GraphQLInterpreter[R, CalibanError]
  ): GraphQLInterpreter[R, CalibanError] =
    interpreter.mapError {
      case err @ ExecutionError(_, _, _, Some(wrappedError: FinitoError), _) =>
        err.copy(
          msg = wrappedError.getMessage,
          extensions = ObjectValue(
            List(("errorCode", StringValue(wrappedError.errorCode)))
          ).some
        )
      case err: ValidationError =>
        err.copy(extensions =
          ObjectValue(List(("errorCode", StringValue("VALIDATION_ERROR")))).some
        )
      case err: ParsingError =>
        err.copy(extensions =
          ObjectValue(List(("errorCode", StringValue("PARSING_ERROR")))).some
        )
      case err: ExecutionError =>
        err.copy(extensions =
          ObjectValue(List(("errorCode", StringValue("UNKNOWN")))).some
        )
    }
}
