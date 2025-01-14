package fin.service.book

import java.time.LocalDate

import scala.math.Ordering.Implicits._

import cats.Monad
import cats.effect.Ref
import cats.implicits._

import fin.BookConversions._
import fin.Types._
import fin.implicits._
import fin.persistence.BookRepository

class InMemoryBookRepository[F[_]: Monad](booksRef: Ref[F, List[UserBook]])
    extends BookRepository[F] {

  override def books: F[List[UserBook]] = booksRef.get

  override def createBook(book: BookInput, date: LocalDate): F[Unit] =
    booksRef.update(book.toUserBook(dateAdded = date.some) :: _)

  override def createBooks(books: List[UserBook]): F[Unit] =
    booksRef.update { ls =>
      books
        .filterNot(b => ls.contains_(b))
        .map(_.copy(startedReading = None, lastRead = None)) ::: ls
    }

  override def retrieveBook(isbn: String): F[Option[UserBook]] =
    booksRef.get.map(_.find(_.isbn === isbn))

  override def retrieveMultipleBooks(isbns: List[String]): F[List[UserBook]] =
    booksRef.get.map(_.filter(book => isbns.contains(book.isbn)))

  override def rateBook(book: BookInput, rating: Int): F[Unit] =
    booksRef.getAndUpdate { ls =>
      ls.map { b =>
        if (b.isbn === book.isbn)
          b.copy(rating = rating.some)
        else b
      }
    }.void

  override def addBookReview(book: BookInput, review: String): F[Unit] =
    booksRef.getAndUpdate { ls =>
      ls.map { b =>
        if (b.isbn === book.isbn)
          b.copy(review = review.some)
        else b
      }
    }.void

  override def startReading(book: BookInput, date: LocalDate): F[Unit] =
    for {
      _ <- booksRef.getAndUpdate(_.map { b =>
        if (b.isbn === book.isbn)
          b.copy(startedReading = date.some)
        else b
      })
    } yield ()

  override def finishReading(book: BookInput, date: LocalDate): F[Unit] =
    for {
      _ <- booksRef.getAndUpdate(_.map { b =>
        if (b.isbn === book.isbn)
          b.copy(lastRead = date.some)
        else b
      })
    } yield ()

  override def deleteBookData(isbn: String): F[Unit] =
    booksRef
      .getAndUpdate(_.map { b =>
        if (b.isbn === isbn)
          b.copy(rating = None, startedReading = None, lastRead = None)
        else b
      })
      .void

  override def retrieveBooksInside(
      from: LocalDate,
      to: LocalDate
  ): F[List[UserBook]] = {
    val inRange = (d: LocalDate) => from <= d && d <= to
    booksRef.get.map {
      _.filter(b => b.dateAdded.exists(inRange) || b.lastRead.exists(inRange))
    }
  }
}
