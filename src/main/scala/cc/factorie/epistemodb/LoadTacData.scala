package cc.factorie.epistemodb

import com.mongodb.{DB, MongoClient}

/**
 * Created by beroth on 2/11/15.
 */

class LoadTacDataOptions extends cc.factorie.util.DefaultCmdOptions {
  val tacData = new CmdOption("tac-data", "", "FILE", "tab separated file with TAC training data")
  val mongoHost = new CmdOption("mongo-host","localhost","STRING","host with running mongo db")
  val mongoPort = new CmdOption("mongo-port", 27017, "INT", "port mongo db is running on")
  val dbname = new CmdOption("db-name", "tac", "STRING", "name of mongo db to write data into")
}

object LoadTacDataIntoMongo {

  val opts = new LoadTacDataOptions

  def main(args: Array[String]) : Unit = {
    opts.parse(args)
    val kb = KBMatrix.fromTsv(opts.tacData.value)
    val mongoClient = new MongoClient( opts.mongoHost.value , opts.mongoPort.value )
    val db:DB = mongoClient.getDB( opts.dbname.value )
    kb.writeToMongo(db)
  }
}