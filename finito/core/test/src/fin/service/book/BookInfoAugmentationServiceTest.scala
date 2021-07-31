package fin.service.book

import java.time.LocalDate

import cats.effect._
import cats.effect.concurrent.Ref
import cats.implicits._
import weaver._

import fin.BookConversions._
import fin.Types._
import fin.implicits._

object BookInfoAugmentationServiceTest extends SimpleIOSuite {

  val date     = LocalDate.of(2021, 5, 22)
  val baseBook = BookInput("title", List("author"), "my desc", "isbn", "uri")
  val repo =
    new InMemoryBookRepository[IO](Ref.unsafe[IO, List[UserBook]](List.empty))

  test("search is augemented with data") {
    val book1 = baseBook.copy(isbn = "isbn for search #1")
    val book2 = baseBook.copy(isbn = "isbn for search #2")
    val service =
      BookInfoAugmentationService(
        new MockedInfoService(toUserBook(book2)),
        repo
      )
    val rating = 4
    for {
      _        <- repo.createBook(book1, date)
      _        <- repo.createBook(book2, date)
      _        <- repo.rateBook(book2, rating)
      _        <- repo.startReading(book2, date)
      response <- service.search(QueriesBooksArgs(None, None, None, None))
      List(bookResponse) = response
    } yield expect(
      bookResponse === toUserBook(
        book2,
        rating = rating.some,
        startedReading = date.some
      )
    )
  }

  test("fromIsbn is augmented with data") {
    val book = baseBook.copy(isbn = "isbn for fromIsbn")
    val service =
      BookInfoAugmentationService(
        new MockedInfoService(toUserBook(book)),
        repo
      )
    val rating = 4
    for {
      _            <- repo.createBook(book, date)
      _            <- repo.rateBook(book, rating)
      _            <- repo.startReading(book, date)
      bookResponse <- service.fromIsbn(QueriesBookArgs(book.isbn, None))
    } yield expect(
      bookResponse === toUserBook(
        book,
        rating = rating.some,
        startedReading = date.some
      )
    )
  }
}

class MockedInfoService(book: UserBook) extends BookInfoService[IO] {

  override def search(booksArgs: QueriesBooksArgs): IO[List[UserBook]] =
    List(book).pure[IO]

  override def fromIsbn(bookArgs: QueriesBookArgs): IO[UserBook] = book.pure[IO]
}
