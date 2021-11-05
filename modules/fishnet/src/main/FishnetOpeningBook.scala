package lila.fishnet

import chess.format.Forsyth
import chess.format.Uci
import chess.Speed
import com.softwaremill.tagging._
import play.api.libs.json._
import play.api.libs.ws.JsonBodyReadables._
import play.api.libs.ws.StandaloneWSClient
import scala.concurrent.ExecutionContext

import lila.common.Json.uciReader
import lila.common.ThreadLocalRandom
import lila.game.Game
import lila.memo.SettingStore

final private class FishnetOpeningBook(
    ws: StandaloneWSClient,
    depth: SettingStore[Int] @@ FishnetOpeningBook.Depth
)(implicit ec: ExecutionContext) {

  import FishnetOpeningBook._

  def apply(game: Game, level: Int): Fu[Option[Uci]] = (game.turns < depth.get()) ?? {
    ws.url(endpoint)
      .withQueryStringParameters(
        "variant"     -> game.variant.key,
        "fen"         -> Forsyth.>>(game.chess).value,
        "topGames"    -> "0",
        "recentGames" -> "0",
        "ratings"     -> (~levelRatings.get(level)).mkString(","),
        "speeds"      -> (~openingSpeeds.get(game.speed)).map(_.key).mkString(",")
      )
      .get()
      .map {
        case res if res.status != 200 =>
          logger.warn(s"opening book ${game.id} ${level} ${res.status} ${res.body}")
          none
        case res =>
          for {
            data <- res.body[JsValue].validate[Response](responseReader).asOpt
            move <- data.randomPonderedMove
          } yield move.uci
      }
      .monValue(uci =>
        _.fishnet
          .openingBook(level = level, variant = game.variant.key, ply = game.turns, hit = uci.isDefined)
      )
  }
}

object FishnetOpeningBook {

  trait Depth

  case class Response(moves: List[Move]) {
    def randomPonderedMove: Option[Move] = moves.size match {
      case 0 => none
      case size =>
        val sum = moves.map(_.nb).sum / size
        val rng = ThreadLocalRandom nextInt sum
        moves
          .foldLeft((none[Move], 0)) { case ((found, it), next) =>
            val nextIt = it + next.nb
            (found orElse (nextIt > rng).option(next), nextIt)
          }
          ._1
          .orElse(moves.headOption)
    }
  }

  case class Move(uci: Uci, white: Int, draws: Int, black: Int) {
    def nb = white + draws + black
  }

  implicit val moveReader     = Json.reads[Move]
  implicit val responseReader = Json.reads[Response]

  private val endpoint = "https://explorer.lichess.ovh/lichess"

  private val levelRatings: Map[Int, Seq[Int]] = Map(
    1 -> Seq(1600),
    2 -> Seq(1600, 1800),
    3 -> Seq(1800, 2000),
    4 -> Seq(1800, 2000, 2200),
    5 -> Seq(1800, 2000, 2200),
    6 -> Seq(2000, 2200, 2500),
    7 -> Seq(2200, 2500),
    8 -> Seq(2500)
  )

  private val openingSpeeds: Map[Speed, Seq[Speed]] = {
    import Speed._
    Map(
      UltraBullet    -> Seq(UltraBullet, Bullet),
      Bullet         -> Seq(Bullet, Blitz),
      Blitz          -> Seq(Bullet, Blitz, Rapid),
      Rapid          -> Seq(Blitz, Rapid, Classical),
      Classical      -> Seq(Rapid, Classical, Correspondence),
      Correspondence -> Seq(Rapid, Classical, Correspondence)
    )
  }
}