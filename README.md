# mod-mysql-postgresql

This Vert.x module uses the https://github.com/mauricio/postgresql-async drivers to support a fully async module for MySQL and PostgreSQL.

## Requirements

* Vert.x 2.0+
* Current mod-lang-scala commit (https://github.com/vert-x/mod-lang-scala/tree/b3918f28ef50de33102a0453475e8c9d9b760526)

## Installation

`vertx install io.vertx.mod-mysql-postgresql`

## Configuration

    {
      "address" : <event-bus-addres-to-listen-on>,
      "connection" : <MySQL|PostgreSQL>,
      "host" : <your-host>,
      "port" : <your-port>,
      "username" : <your-username>,
      "password" : <your-password>,
      "database" : <name-of-your-database>
    }

* `address` - The address this module should register on the event bus. Defaults to `campudus.asyncdb`
* `connection` - The database you want to use. Defaults to `PostgreSQL`.
* `host` - The host of the database. Defaults to `localhost`.
* `port` - The port of the database. Defaults to `5432` for PostgreSQL and `3306` for MySQL.
* `username` - The username to connect to the database. Defaults to `postgres` for PostgreSQL and `root` for MySQL.
* `password` - The password to connect to the database. Default is not set, i.e. it uses no password.
* `database` - The name of the database you want to connect to. Defaults to `test`.


## Usage

All commands are relatively similar. Use JSON with the `action` field and add the needed parameters for each command.

There are only a few commands available currently, but in theory you should be able to invoke any command on the database with the `raw` action.

### Reply messages

The module will reply to all requests. In the message, there will be either a `"status" : "ok"` or a `"status" : "error"`. If the request could be processed without problems, it will result in an "ok" status. See an example here:

    {
      "status" : "ok",
      "rows" : 2,
      "message" : "SELECT 2",
      "fields" : [ "name", "email", "is_male", "age", "money", "wedding_date" ],
      "results" : [
        ["Mr. Test", "mr-test@example.com", true, 32, 123.45, "2014-04-04"],
        ["Mrs. Test", "mrs-test@example.com", false, 16, 543.21, "2022-02-22"]
      ]
    } 

* `rows` gives you the number of rows affected by the statement sent to the server.
* `message` is a status message from the server.
* `fields` contains the list of fields of the selected table - Only present if the request resulted in a result set.
* `results` contains a list of rows - Only present if the request resulted in a result set.

If the request resulted in an error, a possible reply message looks like this:

    {
      "status" : "error",
      "message" : "message":"column \"ager\" does not exist"
    }

### insert

Use this action to insert new rows into a table. You need to specify a table, the fields to insert and an array of rows to insert. The rows itself are an array of values.

    {
      "action" : "insert",
      "table" : "some_test",
      "fields" : ["name", "email", "is_male", "age", "money", "wedding_date"],
      "values" : [
        ["Mr. Test", "mr-test@example.com", true, 32, 123.45, "2014-04-04"],
        ["Mrs. Test", "mrs-test@example.com", false, 16, 543.21, "2022-02-22"]
      ]
    }

### select

The `select` action creates a `SELECT` statement to get a projection from a table. You can filter the columns by providing a `fields` array. If you omit the `fields` array, it selects every column available in the table.

    {
      "action" : "select",
      "table" : "some_test",
      "fields" : ["name", "email", "is_male", "age", "money", "wedding_date"]
    }

### raw - Raw commands

Use this action to send arbitrary commands to the database. You should be able to do submit any query or insertion with this command. 

Here is an example for creating a table in PostgreSQL:

    {
      "action" : "raw",
      "command" : "CREATE TABLE some_test (
                     id SERIAL,
                     name VARCHAR(255),
                     email VARCHAR(255),
                     is_male BOOLEAN,
                     age INT,
                     money FLOAT,
                     wedding_date DATE
                   );"
    }

And if you want to drop it again, you can send the following:

    {
      "action" : "raw",
      "command" : "DROP TABLE some_test;"
    }

## Planned actions

You can always use `raw` to do anything on the database. If the statement is a query, it will return its results just like a `select`.

The `select`, `insert` and following actions just for you to be able to have a cross-database application in the end. If you do not use `raw`, these commands should create the needed statements for you.

* `update` - Updates rows of a table
* `delete` - Deletes rows from a table
* `prepared` - Create a prepared statement
* `create` - Creates a table
* `drop` - Drops a table
* `transaction` - Create a transaction

These actions are currently not available, but they should be implemented soon. Please see the following examples and send feedback:

    { // UPDATE some_test SET age=age+1 WHERE id=1
      "action" : "update",
      "table" : "some_test",
      "set" : "age=age+1",
      "condition" : "id=1"
    }

    { // DELETE FROM some_test WHERE id = 5
      "action" : "delete",
      "table" : "some_test",
      "condition" : "id = 5"
    }

    // SELECT name, email FROM some_test WHERE is_male=? AND money >= ?
    {
      "action" : "prepared",
      "statement" : "SELECT name, email FROM some_test WHERE is_male=? AND money >= ?",
      "values" : [true,100]
    }

Not sure about the next one, since it would need quite some work and thought for the cross-database types.

    // CREATE TABLE some_test (
    //   id SERIAL,
    //   name VARCHAR(255),
    //   email VARCHAR(255),
    //   age INTEGER
    // );
    { 
      "action" : "create",
      "table" : "some_test",
      "fields" : ["id PRIMARY KEY", "name VARCHAR(255)", "email VARCHAR(255)", "age INTEGER"]
    }

    { // DROP TABLE some_test
      "action" : "drop",
      "table" : "some_test",
    }

This one should be a nice to have feature:

    {
      "action" : "transaction",
      "statements" : [
        { "action" : "update", "table" : "account", "set" : "money=money-50", "condition" : "id=1" },
        { "action" : "update", "table" : "inventory", "set" : "cats=cats+1", "condition" : "account_id=1" }
      ]
    }
