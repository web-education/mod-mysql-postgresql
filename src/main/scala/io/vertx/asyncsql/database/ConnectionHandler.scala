package io.vertx.asyncsql.database

import scala.collection.JavaConverters.iterableAsScalaIterableConverter
import scala.concurrent.Future
import org.vertx.scala.core.json.{JsonElement, JsonArray, JsonObject, Json}
import org.vertx.scala.core.logging.Logger
import com.github.mauricio.async.db.{ Configuration, Connection, QueryResult, RowData }
import com.github.mauricio.async.db.postgresql.exceptions.GenericDatabaseException
import io.vertx.asyncsql.database.pool.AsyncConnectionPool
import org.vertx.scala.mods.ScalaBusMod
import org.vertx.scala.mods.replies._
import org.vertx.scala.core.Vertx
import org.vertx.scala.platform.Container
import io.vertx.asyncsql.Starter
import org.vertx.scala.mods.ScalaBusMod.Receive
import scala.collection.mutable.ListBuffer

trait ConnectionHandler extends ScalaBusMod {
  val verticle: Starter
  def dbType: String
  val config: Configuration
  val maxPoolSize: Int

  lazy val vertx: Vertx = verticle.vertx
  lazy val container: Container = verticle.container
  lazy val logger: Logger = verticle.logger
  lazy val pool = AsyncConnectionPool(verticle, dbType, maxPoolSize, config)

  def transactionStart: String = "START TRANSACTION;"
  def transactionEnd: String = "COMMIT;"
  def statementDelimiter: String = ";"

  import org.vertx.scala.core.eventbus._
  override def receive: Receive = (msg: Message[JsonObject]) => {
    case "select" => select(msg.body)
    case "insert" => insert(msg.body)
    case "prepared" => AsyncReply(sendWithPool(prepared(msg.body)))
    case "transaction" => transaction(msg.body)
    case "raw" => AsyncReply(sendWithPool(rawCommand(msg.body.getString("command"))))
  }

  def close() = pool.close()

  protected def escapeField(str: String): String = "\"" + str.replace("\"", "\"\"") + "\""
  protected def escapeString(str: String): String = "'" + str.replace("'", "''") + "'"

  protected def escapeValue(v: Any): String = v match {
    case null => "NULL"
    case x: Int => x.toString
    case x: Boolean => x.toString
    case x => escapeString(x.toString)
  }

  protected def selectCommand(json: JsonObject): String = {
    val table = json.getString("table")
    Option(json.getArray("fields")) match {
      case Some(fields) => fields.asScala.toStream.map(elem => escapeField(elem.toString)).mkString("SELECT ", ",", " FROM " + table)
      case None => "SELECT * FROM " + table
    }
  }

  protected def select(json: JsonObject): AsyncReply = AsyncReply {
    sendWithPool(rawCommand(selectCommand(json)))
  }

  protected def insertCommand(json: JsonObject): String = {
    val table = json.getString("table")
    val fields = json.getArray("fields").asScala
    val lines = json.getArray("values").asScala
    val returning = Option(json.getString("returning"))
    val listOfLines = for {
      line <- lines
    } yield {
      line.asInstanceOf[JsonArray].asScala.toStream.map(v => escapeValue(v)).mkString("(", ",", ")")
    }
    val sb = new StringBuilder("INSERT INTO ")
      .append(table)
      .append(" ")
      .append(fields.map(f => escapeField(f.toString)).mkString("(", ",", ")"))
      .append(" VALUES ")
      .append(listOfLines.mkString(","))
    returning.map(c => sb.append(" RETURNING ").append(c))
    sb.toString()
  }

  protected def insert(json: JsonObject): AsyncReply = AsyncReply {
    sendWithPool(rawCommand(insertCommand(json)))
  }

  sealed trait CommandType { val query: Connection => Future[QueryResult] }
  case class Raw(stmt: String) extends CommandType { val query = rawCommand(stmt) }
  case class Prepared(json: JsonObject) extends CommandType { val query = prepared(json) }

  protected def transaction(json: JsonObject): AsyncReply = AsyncReply(pool.withConnection({ c: Connection =>
    logger.info("TRANSACTION-JSON: " + json.encodePrettily())

    Option(json.getArray("statements")) match {
      case Some(statements) => c.inTransaction {
        def appendResult(result: Any, results: ListBuffer[JsonObject]) {
          results.append(result match {
            case r: QueryResult =>
              val res = buildResults(r).toJson
              logger.debug(res)
              res
            case _ => Json.obj()
          })
        }
        conn: Connection =>
        val futures = statements.asScala.map {
          case js: JsonObject =>
            js.getString("action") match {
              case "select" => Raw(selectCommand(js))
              case "insert" => Raw(insertCommand(js))
              case "prepared" => Prepared(js)
              case "raw" => Raw(js.getString("command"))
            }
          case _ => throw new IllegalArgumentException("'statements' needs JsonObjects!")
        }
        val results = ListBuffer[JsonObject]()
        val f = futures.foldLeft(Future[Any]()) { case (fut, cmd) =>
          fut flatMap {result =>
            appendResult(result, results)
            cmd.query(conn)
          }
        }

        f map {result =>
          appendResult(result, results)
          Ok(Json.obj("results" -> results.tail))
        }
      }
      case None => throw new IllegalArgumentException("No 'statements' field in request!")
    }
  }))

  
  protected def sendWithPool(fn: Connection => Future[QueryResult]): Future[SyncReply] = pool.withConnection({ c: Connection =>
    fn(c) map buildResults recover {
      case x: GenericDatabaseException =>
        Error(x.errorMessage.message)
      case x =>
        Error(x.getMessage())
    }
  })

  protected def prepared(json: JsonObject): Connection => Future[QueryResult] = { c: Connection =>
    c.sendPreparedStatement(json.getString("statement"), json.getArray("values").toArray())
  }

  protected def rawCommand(command: String): Connection => Future[QueryResult] = { c: Connection => c.sendQuery(command) }

  private def buildResults(qr: QueryResult): SyncReply = {
    val result = new JsonObject()
    result.putString("message", qr.statusMessage)
    result.putNumber("rows", qr.rowsAffected)

    qr.rows match {
      case Some(resultSet) =>
        val fields = (new JsonArray() /: resultSet.columnNames) { (arr, name) =>
          arr.addString(name)
        }

        val rows = (new JsonArray() /: resultSet) { (arr, rowData) =>
          arr.add(rowDataToJsonArray(rowData))
        }

        result.putArray("fields", fields)
        result.putArray("results", rows)
      case None =>
    }

    Ok(result)
  }

  private def dataToJson(data: Any): Any = data match {
    case null => null
    case x: Boolean => x
    case x: Number => x
    case x: String => x
    case x: Array[Byte] => x
    case x: JsonElement => x
    case x => x.toString()
  }

  private def rowDataToJsonArray(rowData: RowData): JsonArray = Json.arr(rowData.map(dataToJson).toList: _*)
}
