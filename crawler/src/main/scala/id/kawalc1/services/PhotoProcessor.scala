package id.kawalc1.services

import akka.NotUsed
import akka.stream.Materializer
import akka.stream.scaladsl.{ Source => StreamSource }
import id.kawalc1
import id.kawalc1.clients.{ Extraction, JsonSupport, KawalC1Client, KawalPemiluClient }
import id.kawalc1.database.ResultsTables.{ AlignResults, ExtractResults }
import id.kawalc1.database.TpsTables.{ Tps, Kelurahan => KelurahanTable }
import id.kawalc1.database._
import id.kawalc1._
import org.json4s.native.Serialization
import slick.dbio.Effect
import slick.jdbc.SQLiteProfile
import slick.jdbc.SQLiteProfile.api._
import slick.sql.FixedSqlAction

import scala.concurrent.duration.{ FiniteDuration, _ }
import scala.concurrent.{ ExecutionContext, Future }

case class AlignedPicture(url: String, imageSize: Int)

class PhotoProcessor(kawalPemiluClient: KawalPemiluClient)(implicit
  val ex: ExecutionContext,
  mat: Materializer)
  extends JsonSupport
  with BlockingSupport {
  override def duration: FiniteDuration = 1.hour

  val ImageSize = 1280
  val FeatureAlgorithm = "akaze"
  val url = "http://lh3.googleusercontent.com/HZ6AJF6YYqA2M5MXxH99XedoaE1Rk3-IelJEsnosBVPLdMb73X0w7T5_mWvExCsIZlI-cud3kSU9Lk7c700"

  private def transform[A, B](
    sourceDb: SQLiteProfile.backend.Database,
    targetDb: SQLiteProfile.backend.Database,
    query: Seq[A],
    process: (Seq[A], Int, KawalC1Client) => Future[Seq[B]],
    insert: Seq[B] => Seq[FixedSqlAction[Int, NoStream, Effect.Write]],
    threads: Int,
    kawalC1Client: KawalC1Client) = {
    for {
      processed <- process(query, threads, kawalC1Client)
      inserted <- targetDb.run(DBIO.sequence(insert(processed)))
    } yield inserted

  }

  private def batchTransform[A, B, C <: Table[A]](
    sourceDb: SQLiteProfile.backend.Database,
    targetDb: SQLiteProfile.backend.Database,
    client: KawalC1Client,
    query: Query[C, C#TableElementType, Seq],
    process: (Seq[A], Int, KawalC1Client) => Future[Seq[B]],
    insert: Seq[B] => Seq[FixedSqlAction[Int, NoStream, Effect.Write]],
    params: BatchParams) = {
    var numberOfItems = 0
    var start: Long = params.start
    do {
      val nextBatch = query.drop(start).take(params.batchSize)
      val items = sourceDb.run(nextBatch.result).futureValue
      numberOfItems = items.length
      val inserted = transform(sourceDb, targetDb, items, process, insert, params.threads, client).futureValue
      logger.info(s"Inserted batch $start - ${start + params.batchSize}. # of items: ${inserted.length}")
      start += params.batchSize
    } while (numberOfItems > 0)
    start + numberOfItems
  }

  def align(
    sourceDb: SQLiteProfile.backend.Database,
    targetDb: SQLiteProfile.backend.Database,
    client: KawalC1Client,
    params: BatchParams): Long = {

    batchTransform[SingleTps, AlignResult, Tps](
      sourceDb,
      targetDb,
      client,
      ResultsTables.tpsToAlignQuery,
      //      ResultsTables.alignErrorQuery,
      //      ResultsTables.singleTpsQuery(
      //        "http://lh3.googleusercontent.com/9AcbXtQtluaHTyiBi76trcZFqsvG0OP2fw8TIMzuyASPGFcwXJKs-eijqC-CpJv07TwE3_XkSN2hkFJT0Q"),
      alignPhoto,
      ResultsTables.upsertAlign,
      params)
  }

  def fetch(
    sourceDb: SQLiteProfile.backend.Database,
    targetDb: SQLiteProfile.backend.Database,
    client: KawalC1Client,
    params: BatchParams): Long = {

    batchTransform[KelurahanId, Seq[SingleTps], KelurahanTable](
      sourceDb,
      targetDb,
      client,
      TpsTables.kelurahanQuery, //.filter(_.idKel === 82193),
      fetchTps,
      TpsTables.upsertTps,
      params)
  }

  def fetchTps(kelurahan: Seq[KelurahanId], threads: Int, client: KawalC1Client) = {
    streamResults(kelurahan.map(_.idKel.toLong), getSingleLurah, threads, client)
  }

  def getSingleLurah(number: Long, kawalC1Client: KawalC1Client) = {
    kawalPemiluClient
      .getKelurahan(number)
      .map {
        case Right(kel) => Kelurahan.toTps(kel)
        case Left(_) => Seq.empty
      }
  }

  def extract(
    sourceDb: SQLiteProfile.backend.Database,
    targetDb: SQLiteProfile.backend.Database,
    client: KawalC1Client,
    params: BatchParams): Long =
    batchTransform[AlignResult, ExtractResult, AlignResults](
      sourceDb,
      targetDb,
      client,
      ResultsTables.tpsToExtractQuery,
      extractNumbers,
      ResultsTables.upsertExtract,
      params)

  def processProbabilities(
    sourceDb: SQLiteProfile.backend.Database,
    targetDb: SQLiteProfile.backend.Database,
    client: KawalC1Client,
    params: BatchParams): Long =
    batchTransform[ExtractResult, PresidentialResult, ExtractResults](
      sourceDb,
      targetDb,
      client,
      ResultsTables.extractResultsQuery.filter(_.responseCode === 200),
      processProbabilities,
      ResultsTables.upsertPresidential,
      params)

  def alignPhoto(tps: Seq[SingleTps], threads: Int, client: KawalC1Client): Future[Seq[AlignResult]] = {
    streamResults(tps, alignSinglePhoto, threads, client)
  }

  private def alignSinglePhoto(tps: SingleTps, client: KawalC1Client): Future[AlignResult] = {
    val photo: Array[String] = tps.photo.split("/")
    val photoUrl = photo(photo.length - 1)
    val formType = tps.verification.c1.get.`type`
    val formConfig = kawalc1.formTypeToConfig(formType)
    client.alignPhoto(tps.kelurahanId, tps.tpsId, photoUrl, ImageSize, formConfig, FeatureAlgorithm).map {
      case Right(t) =>
        t.transformedUrl match {
          case Some(trans) =>
            AlignResult(
              tps.kelurahanId,
              tps.tpsId,
              Serialization.write(t),
              200,
              tps.photo,
              ImageSize,
              t.similarity,
              formConfig,
              FeatureAlgorithm,
              Some(trans),
              None,
              Some(t.hash))
          case None =>
            AlignResult(
              tps.kelurahanId,
              tps.tpsId,
              Serialization.write(t),
              200,
              tps.photo,
              ImageSize,
              -1.0,
              formConfig,
              FeatureAlgorithm,
              None,
              None,
              Some(t.hash))
        }
      case Left(resp) =>
        AlignResult(
          tps.kelurahanId,
          tps.tpsId,
          resp.response,
          resp.code,
          tps.photo,
          ImageSize,
          -1.0,
          formConfig,
          FeatureAlgorithm,
          None,
          None,
          None)
    }
  }

  private def extractSingleResult(res: AlignResult, kawalC1Client: KawalC1Client) = {
    val alignedLastSegment = res.alignedUrl.get.split("/")
    val alignedFileName = alignedLastSegment(alignedLastSegment.length - 1)
    for {
      extracted <- kawalC1Client.extractNumbers(res.id, res.tps, alignedFileName, res.config)
    } yield {
      extracted match {
        case Right(e: Extraction) =>
          ExtractResult(res.id, res.tps, res.photo, Serialization.write(e), 200, e.digitArea, res.config, "")
        case Left(resp) =>
          ExtractResult(res.id, res.tps, res.photo, resp.response, resp.code, "", res.config, "")
      }
    }
  }

  private def extractNumbers(results: Seq[AlignResult], threads: Int, kawalC1Client: KawalC1Client): Future[Seq[ExtractResult]] = {
    streamResults(results, extractSingleResult, threads, kawalC1Client)
  }

  private def streamResults[A: Manifest, B](
    toProcess: Seq[A],
    func: (A, KawalC1Client) => Future[B],
    threads: Int,
    client: KawalC1Client): Future[Seq[B]] = {
    val futures: StreamSource[B, NotUsed] =
      StreamSource[A](toProcess.toList)
        .mapAsync(threads)(x => func(x, client))

    futures.runFold(Seq.empty[B])(_ :+ _)
  }

  private def processProbabilities(
    results: Seq[ExtractResult],
    threads: Int,
    kawalC1Client: KawalC1Client): Future[Seq[PresidentialResult]] = {
    streamResults(results, procesExtractResults, threads, kawalC1Client)
  }

  private def getEmptyResult(res: ExtractResult, code: Int, response: String) = {
    PresidentialResult(
      id = res.id,
      tps = res.tps,
      photo = res.photo,
      response = response,
      responseCode = code,
      pas1 = -1,
      pas2 = -1,
      jumlahCalon = -1,
      calonConfidence = -1.0,
      jumlahSah = -1,
      tidakSah = -1,
      jumlahSeluruh = -1,
      jumlahConfidence = -1)
  }

  private def procesExtractResults(res: ExtractResult, kawalC1Client: KawalC1Client) = {
    for {
      extracted <- {
        val extraction = Serialization.read[Extraction](res.response)
        kawalC1Client.processProbabilities(res.id, res.tps, extraction.numbers, res.config)
      }
    } yield {

      extracted match {
        case Right(p: ProbabilitiesResponse) =>
          val first = p.probabilityMatrix.head.headOption
          val second = p.probabilityMatrix.tail.head.headOption

          val pas1 = first.map(_.numbers.filter(_.shortName == "jokowi").head.number).getOrElse(-1)
          val pas2 = first.map(_.numbers.filter(_.shortName == "prabowo").head.number).getOrElse(-1)
          val jumlahCalon = first.map(_.numbers.filter(_.shortName == "jumlah").head.number).getOrElse(-1)
          val calonConfidence = first.map(_.confidence).getOrElse(0.0)

          val jumlah = second.map(_.numbers.filter(_.shortName == "jumlah").head.number).getOrElse(-1)
          val tidakSah = second.map(_.numbers.filter(_.shortName == "tidakSah").head.number).getOrElse(-1)
          val jumlahSeluruh = second.map(_.numbers.filter(_.shortName == "jumlahSeluruh").head.number).getOrElse(-1)
          val jumlahConfidence = second.map(_.confidence).getOrElse(0.0)

          PresidentialResult(
            id = res.id,
            tps = res.tps,
            photo = res.photo,
            response = Serialization.write(p),
            responseCode = 200,
            pas1 = pas1,
            pas2 = pas2,
            jumlahCalon = jumlahCalon,
            calonConfidence = calonConfidence,
            jumlahSah = jumlah,
            tidakSah = tidakSah,
            jumlahSeluruh = jumlahSeluruh,
            jumlahConfidence = jumlahConfidence)

        case Left(resp) =>
          getEmptyResult(res, resp.code, resp.response)
      }

    }
  }
}
