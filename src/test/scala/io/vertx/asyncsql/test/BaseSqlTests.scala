package io.vertx.asyncsql.test

import scala.concurrent.Future
import org.vertx.scala.core.json.{Json, JsonArray}
import org.vertx.testtools.VertxAssert._
import org.junit.Test

trait BaseSqlTests {
  this: SqlTestVerticle =>

  private def withTable[X](tableName: String)(fn: => Future[X]) = {
    (for {
      _ <- createTable(tableName)
      sth <- fn
      _ <- dropTable(tableName)
    } yield sth) recoverWith {
      case x =>
        dropTable(tableName) map (_ => throw x)
    }
  }

  private def asyncTableTest[X](tableName: String)(fn: => Future[X]) = asyncTest(withTable(tableName)(fn))

  private def typeTestInsert[X](fn: => Future[X]) = asyncTableTest("some_test") {
    expectOk(insert("some_test",
      new JsonArray( """["name","email","is_male","age","money","wedding_date"]"""),
      new JsonArray( """[["Mr. Test","test@example.com",true,15,167.31,"2024-04-01"],
            ["Ms Test2","test2@example.com",false,43,167.31,"1997-12-24"]]"""))) flatMap {
      _ =>
        fn
    }
  }

  @Test
  def simpleConnection(): Unit = asyncTest {
    expectOk(raw("SELECT 0")) map {
      reply =>
        val res = reply.getArray("results")
        assertEquals(1, res.size())
        assertEquals(0, res.get[JsonArray](0).get[Number](0).intValue())
    }
  }

  @Test
  def poolSize(): Unit = asyncTest {
    val n = 10
    val futures = for {
      i <- 1 to n
    } yield {
      expectOk(raw("SELECT " + i)) map {
        reply =>
          val res = reply.getArray("results")
          assertEquals(1, res.size())
          val result = res.get[JsonArray](0).get[Number](0).intValue()
          assertEquals(i, result)
          result
      }
    }

    val fs = Future.sequence(futures) map (_.sum)
    fs map (assertEquals((n * (n + 1)) / 2, _))
  }

  @Test
  def multipleFields(): Unit = asyncTest {
    expectOk(raw("SELECT 1 a, 0 b")) map {
      reply =>
        val res = reply.getArray("results")
        assertEquals(1, res.size())
        val firstElem = res.get[JsonArray](0)
        assertEquals(1, firstElem.get[Number](0).intValue())
        assertEquals(0, firstElem.get[Number](1).intValue())
    }
  }

  @Test
  def multipleFieldsOrder(): Unit = typeTestInsert {
    import scala.collection.JavaConverters._
    expectOk(raw("SELECT is_male, age, email, money, name FROM some_test WHERE is_male = true")) map {
      reply =>
        val receivedFields = reply.getArray("fields")
        val results = reply.getArray("results").get[JsonArray](0)

        assertEquals(1, reply.getInteger("rows"))

        val columnNamesList = receivedFields.asScala.toList

        assertEquals("Mr. Test", results.get(columnNamesList.indexOf("name")))
        assertEquals("test@example.com", results.get(columnNamesList.indexOf("email")))
        assertEquals(15, results.get[Int](columnNamesList.indexOf("age")))
        assertTrue(results.get[Any](columnNamesList.indexOf("is_male")) match {
          case b: Boolean => b
          case i: Number => i.intValue() == 1
          case x => false
        })
        assertEquals(167.31, results.get[Number](columnNamesList.indexOf("money")).doubleValue(), 0.01)
    }
  }

  @Test
  def createAndDropTable(): Unit = asyncTest {
    createTable("some_test") flatMap (_ => dropTable("some_test")) map {
      reply =>
        assertEquals(0, reply.getInteger("rows"))
    }
  }

  @Test
  def insertCorrect(): Unit = asyncTableTest("some_test") {
    expectOk(insert("some_test", new JsonArray( """["name","email"]"""), new JsonArray( """[["Test","test@example.com"],["Test2","test2@example.com"]]""")))
  }

  @Test
  def insertNullValues(): Unit = asyncTableTest("some_test") {
    expectOk(insert("some_test", new JsonArray( """["name","email"]"""), new JsonArray( """[[null,"test@example.com"],[null,"test2@example.com"]]""")))
  }

  @Test
  def insertTypeTest(): Unit = typeTestInsert {
    Future.successful()
  }

  @Test
  def insertMaliciousDataTest(): Unit = asyncTableTest("some_test") {
    // If this SQL injection works, the drop table of asyncTableTest would throw an exception
    expectOk(insert("some_test",
      new JsonArray( """["name","email","is_male","age","money","wedding_date"]"""),
      new JsonArray( """[["Mr. Test","test@example.com",true,15,167.31,"2024-04-01"],
            ["Ms Test2','some@example.com',false,15,167.31,'2024-04-01');DROP TABLE some_test;--","test2@example.com",false,43,167.31,"1997-12-24"]]""")))
  }

  @Test
  def insertUniqueProblem(): Unit = asyncTableTest("some_test") {
    expectError(insert("some_test", new JsonArray( """["name","email"]"""), new JsonArray( """[["Test","test@example.com"],["Test","test@example.com"]]"""))) map {
      reply =>
        logger.info("expected error: " + reply.encode())
    }
  }

  @Test
  def selectWithoutFields(): Unit = typeTestInsert {
    expectOk(select("some_test")) map {
      reply =>
        val receivedFields = reply.getArray("fields")
        logger.info("received: " + receivedFields.encode())

        def assertFieldName(field: String) = {
          assertTrue("fields should contain '" + field + "'", receivedFields.contains(field))
        }
        assertFieldName("id")
        assertFieldName("name")
        assertFieldName("email")
        assertFieldName("is_male")
        assertFieldName("age")
        assertFieldName("money")
        assertFieldName("wedding_date")
        val moneyField = receivedFields.toArray().indexOf("money")

        val mrTest = reply.getArray("results").get[JsonArray](0)
        assertTrue(mrTest.contains("Mr. Test"))
        assertTrue(mrTest.contains("test@example.com"))
        assertTrue(mrTest.contains(true) || mrTest.contains(1))
        assertTrue(mrTest.contains(15))
        assertEquals(167.31, mrTest.get[Number](moneyField).doubleValue(), 0.0001)
    }
  }

  @Test
  def selectEverything(): Unit = typeTestInsert {
    val fieldsArray = Json.arr("name", "email", "is_male", "age", "money", "wedding_date")
    expectOk(select("some_test", fieldsArray)) map {
      reply =>
        val receivedFields = reply.getArray("fields")
        checkSameFields(fieldsArray, receivedFields)
        val results = reply.getArray("results")
        val mrTest = results.get[JsonArray](0)
        checkMrTest(mrTest)
    }
  }

  private def checkSameFields(arr1: JsonArray, arr2: JsonArray) = {
    import scala.collection.JavaConversions._
    arr1.foreach(elem => assertTrue(arr2.contains(elem)))
  }

  private def checkTestPerson(mrOrMrs: JsonArray) = {
    mrOrMrs.get[String](0) match {
      case "Mr. Test" => checkMrTest(mrOrMrs)
      case "Mrs. Test" => checkMrsTest(mrOrMrs)
    }
  }

  private def checkMrTest(mrTest: JsonArray) = {
    assertEquals("Mr. Test", mrTest.get[String](0))
    assertEquals("test@example.com", mrTest.get[String](1))
    assertTrue(mrTest.get[Any](2) match {
      case b: Boolean => b
      case i: Number => i.intValue() == 1
      case x => false
    })
    assertEquals(15, mrTest.get[Number](3).intValue())
    assertEquals(167.31, mrTest.get[Number](4).doubleValue(), 0.0001)
    // FIXME check date conversion
    // assertEquals("2024-04-01", mrTest.get[JsonObject](5))
  }

  private def checkMrsTest(mrsTest: JsonArray) = {
    assertEquals("Mrs. Test", mrsTest.get[String](0))
    assertEquals("test2@example.com", mrsTest.get[String](1))
    assertEquals(false, mrsTest.get[Boolean](2))
    assertEquals(43L, mrsTest.get[Long](3))
    assertEquals(167.31, mrsTest.get[Number](4).doubleValue(), 0.0001)
    // FIXME check date conversion
    // assertEquals("1997-12-24", mrsTest.get[JsonObject](5))
  }

  @Test
  def selectFiltered(): Unit = typeTestInsert {
    val fieldsArray = new JsonArray( """["name","email"]""")
    expectOk(select("some_test", fieldsArray)) map {
      reply =>
        val receivedFields = reply.getArray("fields")
        assertTrue("arrays " + fieldsArray.encode() + " and " + receivedFields.encode() +
          " should match", fieldsArray == receivedFields)
        //      assertEquals(2, reply.getInteger("rows"))
        val results = reply.getArray("results")
        val mrOrMrs = results.get[JsonArray](0)
        mrOrMrs.get[String](0) match {
          case "Mr. Test" =>
            assertEquals("Mr. Test", mrOrMrs.get[String](0))
            assertEquals("test@example.com", mrOrMrs.get[String](1))
          case "Mrs. Test" =>
            assertEquals("Mrs. Test", mrOrMrs.get[String](0))
            assertEquals("test2@example.com", mrOrMrs.get[String](1))
        }
    }
  }

  @Test
  def preparedSelect(): Unit = typeTestInsert {
    expectOk(prepared("SELECT email FROM some_test WHERE name=? AND age=?", Json.arr("Mr. Test", 15))) map {
      reply =>
        val receivedFields = reply.getArray("fields")
        assertEquals(Json.arr("email"), receivedFields)
        //      assertEquals(1, reply.getInteger("rows"))
        assertEquals("test@example.com", reply.getArray("results").get[JsonArray](0).get[String](0))
    }
  }

  @Test
  def transaction(): Unit = typeTestInsert {
    (for {
      a <- expectOk(
        transaction(
          insert("some_test", Json.arr("name", "email", "is_male", "age", "money"),
            Json.arr(Json.arr("Mr. Test jr.", "test3@example.com", true, 5, 2))),
          raw("UPDATE some_test SET age=6 WHERE name = 'Mr. Test jr.'")))
      b <- expectOk(raw("SELECT SUM(age) FROM some_test WHERE is_male = true"))
    } yield b) map {
      reply =>
        val results = reply.getArray("results")
        assertEquals(1, results.size())
        assertEquals(21, results.get[JsonArray](0).get[Number](0).intValue())
    }
  }

  @Test
  def transactionWithPreparedStatement(): Unit = typeTestInsert {
    (for {
      a <- expectOk(
        transaction(
          insert("some_test", Json.arr("name", "email", "is_male", "age", "money"),
            Json.arr(Json.arr("Mr. Test jr.", "test3@example.com", true, 5, 2))),
          prepared("UPDATE some_test SET age=? WHERE name=?", Json.arr(6, "Mr. Test jr."))))
      b <- expectOk(raw("SELECT SUM(age) FROM some_test WHERE is_male = true"))
    } yield b) map {
      reply =>
        val results = reply.getArray("results")
        assertEquals(1, results.size())
        assertEquals(21, results.get[JsonArray](0).get[Number](0).intValue())
    }
  }

}