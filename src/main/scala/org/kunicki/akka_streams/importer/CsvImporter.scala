package org.kunicki.akka_streams.importer

import java.io.{File, FileInputStream}
import java.nio.file.Paths
import java.util.zip.GZIPInputStream

import akka.NotUsed
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Flow, Framing, StreamConverters}
import akka.util.ByteString
import com.typesafe.config.Config
import com.typesafe.scalalogging.LazyLogging
import com.websudos.phantom.dsl.ResultSet
import org.kunicki.akka_streams.model.{InvalidReading, Reading, ValidReading}
import org.kunicki.akka_streams.repository.ReadingRepository

import scala.concurrent.Future
import scala.util.{Failure, Success}

class CsvImporter(config: Config, readingRepository: ReadingRepository)
                 (implicit system: ActorSystem) extends LazyLogging {

  import system.dispatcher

  private val importDirectory = Paths.get(config.getString("importer.import-directory")).toFile
  private val linesToSkip = config.getInt("importer.lines-to-skip")
  private val concurrentFiles = config.getInt("importer.concurrent-files")
  private val concurrentWrites = config.getInt("importer.concurrent-writes")
  private val nonIOParallelism = config.getInt("importer.non-io-parallelism")

  def parseLine(filePath: String)(line: String): Future[Reading] = Future {
    val fields = line.split(";")
    val id = fields(0).toInt
    try {
      val value = fields(1).toDouble
      ValidReading(id, value)
    } catch {
      case t: Throwable =>
        logger.error(s"Unable to parse line in $filePath:\n$line: ${t.getMessage}")
        InvalidReading(id)
    }
  }

  val lineDelimiter: Flow[ByteString, ByteString, NotUsed] =
    Framing.delimiter(ByteString("\n"), 128, allowTruncation = true)

  val parseFile: Flow[File, Reading, NotUsed] =
    Flow[File].flatMapConcat { file =>
      val gzipInputStream = new GZIPInputStream(new FileInputStream(file))

      StreamConverters.fromInputStream(() => gzipInputStream)
        .via(lineDelimiter)
        .drop(linesToSkip)
        .map(_.utf8String)
        .mapAsync(parallelism = nonIOParallelism)(parseLine(file.getPath))
    }

  val computeAverage: Flow[Reading, ValidReading, NotUsed] =
    Flow[Reading].grouped(2).mapAsyncUnordered(parallelism = nonIOParallelism) { readings =>
      Future {
        val validReadings = readings.collect { case r: ValidReading => r }
        val average = if (validReadings.nonEmpty) validReadings.map(_.value).sum / validReadings.size else -1
        ValidReading(readings.head.id, average)
      }
    }

  val storeReadings: Flow[ValidReading, ResultSet, NotUsed] =
    Flow[ValidReading].mapAsyncUnordered(parallelism = concurrentWrites) { reading =>
      readingRepository.save(reading).andThen {
        case Success(_) => logger.info(s"Saved $reading")
        case Failure(e) => logger.error(s"Unable to save $reading: ${e.getMessage}")
      }
    }
}
