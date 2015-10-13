import akka.actor.Actor
import akka.actor.ActorSystem
import akka.actor.Props
import akka.actor.ActorRef
import akka.actor.ActorSelection
import java.net.InetAddress
import com.typesafe.config.ConfigFactory
import akka.routing.RoundRobinRouter
import java.security.MessageDigest
import java.util.regex.Pattern
import java.util.List
import java.util.ArrayList
import akka.routing.ActorRefRoutee
import akka.routing.RoundRobinRoutingLogic
import akka.routing.Router
import akka.routing.RoundRobinPool
import com.typesafe.config.Config

case object Start
case class AssignWorkToClient(msg: String)
case class WorkFromServer(k: Int, start: Long, nrOfElements: Int)
case class StartMining(k: Int, start: Long, nrOfElements: Int)
case class CoinsToClient(coins: List[String])
case class CoinsToServer(coins: List[String])
case object Stop

object Project1 {

  def isValidInputArg(args: Array[String]): (Int, String) = {

    def isValidIP(args: String): Boolean = {

      val pattern = Pattern.compile("^([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])\\." +
        "([01]?\\d\\d?|2[0-4]\\d|25[0-5])$")
      val matcher = pattern.matcher(args)

      return matcher.matches()
    }

    var ip: String = ""
    var k: Int = 0
    var flag: Boolean = true
    var errorMsg: String = ""

    if (args.length == 0 || args.length > 2) {
      errorMsg = "Missing or invalid number of input arguments !!!"
      flag = false
    } else {
      ip = args(0)

      if (ip.contains('.')) {
        if (!isValidIP(ip)) {
          errorMsg = "IP address is invalid or unreachable !!!"
          flag = false
        }
      } else {
        try {
          k = args(0).toInt
          if (k < 1 || k > 255) {
            errorMsg = "Invalid value of k. It should be an integer between 1 & 255 (both inclusive) !!!"
            flag = false
          }
        } catch {
          case ex: NumberFormatException => {
            errorMsg = "Invalid value of k. It should be an integer between 1 & 255 (both inclusive) !!!"
            flag = false
          }
        }
      }
    }

    if (!flag) {
      val mainClassName = "Project1"
      println(errorMsg)
      println("\nUse the following command to run as server:\n>scala " + mainClassName + " <k> (where <k> is the required number of 0s in the bitcoin)\nFor eg. if you want 3 0s as prefix in the bitcoin then run as:\n>scala " + mainClassName + " 3")
      println("\nUse the following command to run as client:\n>scala " + mainClassName + " <server ip> (where <server ip> is the ip the address of the machine on the network where the server program is running)\nFor eg. if the server is running on 192.168.230.180 then run as:\n>scala " + mainClassName + " 192.168.230.180")
      System.exit(0)
    }

    return (k, ip)
  }

  def main(args: Array[String]) {

    val (k, ip) = isValidInputArg(args)

    val localServerSystemName: String = "LocalSystem"
    val remoteClientSystemName: String = "RemoteSystem"

    val config = ConfigFactory.load()
    var serverCustomConfig: String = null

    if (k > 0) {
      //println("Running as server")

      try {
        val hostAddress: String = InetAddress.getLocalHost().getHostAddress()
        serverCustomConfig = """
akka {
  actor {
    provider = "akka.remote.RemoteActorRefProvider"
  }
  remote {
    netty.tcp {
      hostname = """" + hostAddress + """"
      port = 2551
    }
  }
}
"""
      } catch {
        case ex: Exception => {
          serverCustomConfig = null
        }
      }

      val serverConfig = config.getConfig(localServerSystemName)
      var localSystem: ActorSystem = null
      if (null != serverCustomConfig) {
        val customConf = ConfigFactory.parseString(serverCustomConfig);
        localSystem = ActorSystem(localServerSystemName, ConfigFactory.load(customConf).withFallback(serverConfig))
      } else {
        localSystem = ActorSystem(localServerSystemName, serverConfig.withFallback(config))
      }
      //println("System Config: " + serverCustomConfig)
      //println("Local System: " + localSystem)

      var numBitCoins = -1
      try {
        if (args.size == 2) {
          numBitCoins = args(1).toInt
        }
      } catch {
        case ex: Exception => {
          numBitCoins = -1
        }
      }
      
      val server = localSystem.actorOf(Props(classOf[Server], k, numBitCoins, serverConfig), name = "server")
      //println("Server: " + server)

      //System.exit(0)
    } else {
      //println("Running as remote client")

      val serverPath = "akka.tcp://" + localServerSystemName + "@" + ip + ":2551/user/server"

      val remoteConfig = config.getConfig(remoteClientSystemName)
      val remoteSystem = ActorSystem(remoteClientSystemName, remoteConfig.withFallback(config))
      //println("Remote System: " + remoteSystem)

      val remoteClient = remoteSystem.actorOf(Props(classOf[Client], serverPath, remoteConfig), name = "remote_client")
      //println("Remote Client: " + remoteClient)
    }
  }

  class Server(k: Int, numBitCoins: Int, config: Config) extends Actor {
    val client = context.actorOf(Props(classOf[Client], "", config), name = "local_client")
    //println("Local Client: " + client)

    client ! Start

    var start: Long = 0
    val nrOfElements: Int = config.getInt("ClientWorkSize")
    var runningTotal: Int = 0

    def receive = {
      case AssignWorkToClient(msg) => {
        println(msg)
        sender ! WorkFromServer(k, start, nrOfElements)
        start = start + nrOfElements
      }

      case CoinsToServer(coins) => {
        val numCoinsFromClient = coins.size()
        //println("Number of coins from client : " + sender + ": " + numCoinsFromClient)
        if (numCoinsFromClient > 0) {
          for (i <- 0 to numCoinsFromClient - 1)
            println(coins.get(i))
          runningTotal = runningTotal + numCoinsFromClient
        }

        if ((numBitCoins <= 0) || (numBitCoins > 0 && runningTotal < numBitCoins)) {
          sender ! WorkFromServer(k, start, nrOfElements)
          start = start + nrOfElements
        } else {
          self ! Stop
        }
      }

      case Stop => {
        context.system.shutdown()
      }
    }
  }

  class Client(serverPath: String, config: Config) extends Actor {

    var serverRefForRemote: ActorSelection = _
    if (serverPath.length() > 0) {
      serverRefForRemote = context.actorSelection(serverPath)
      //println("Server: " + serverRefForRemote)
      serverRefForRemote ! AssignWorkToClient("Hello From Remote Client: " + self)
    }

    var server: ActorRef = _
    val factor: Double = config.getDouble("NumOfWorkersFactor")
    //println("Factor: "+factor)
    val nrOfWorkers: Int = Math.ceil(Runtime.getRuntime().availableProcessors() * factor).toInt
    //println("Number of Workers: " + nrOfWorkers)

    val workerRouter = context.actorOf(RoundRobinPool(nrOfWorkers).props(Props[Worker]), "workerRouter")
    //val workerRouter = context.actorOf(Props[Worker].withRouter(RoundRobinRouter(nrOfWorkers)), name = "workerRouter")
    //println("Instantiated Worker Router: " + workerRouter)

    var j: Int = 0
    var allWorkerCoins: List[String] = new ArrayList[String]()

    def receive = {
      case Start => {
        sender ! AssignWorkToClient("Hello From Local Client: " + self)
      }

      case WorkFromServer(k, start, nrOfElements) => {
        server = sender
        //println(self + " received work from server: " + start + " " + nrOfElements)
        var startBlock: Long = start
        val blockSize: Int = nrOfElements / (nrOfWorkers)
        var i: Int = 0
        while (i < (nrOfWorkers)) {
          workerRouter ! StartMining(k, startBlock, blockSize)
          startBlock = startBlock + blockSize
          i = i + 1
        }
      }

      case CoinsToClient(coins) => {
        j = j + 1
        //println("Received Coins from worker " + sender + ": " + coins)
        if (coins.size() > 0) {
          //allWorkerCoins.append(coins)
          allWorkerCoins.addAll(coins)
          //println("All Worker Coins: "+allWorkerCoins)
        }

        if (j == (nrOfWorkers)) {
          //println("Sending All Worker Coins to Server "+server+": "+allWorkerCoins)
          server ! CoinsToServer(allWorkerCoins)

          j = 0
          allWorkerCoins = new ArrayList[String]()
        }
      }
    }
  }

  class Worker extends Actor {
    def receive = {
      case StartMining(k, start, nrOfElements) =>
        //println(self + " received work from client " + sender + " " + start + " " + nrOfElements)
        sender ! CoinsToClient(mineBitCoints(k, start, nrOfElements))
    }

    /**
     * This function generates bit coins using SHA-256
     */
    def mineBitCoints(k: Int, start: Long, nrOfElements: Int): List[String] = {
      val id: String = "yaggarwal;"

      var sb: StringBuilder = new StringBuilder(256)
      for (i <- 1 to k) {
        sb.append(0)
      }
      for (i <- (k + 1) to 256) {
        sb.append('f')
      }
      val hexToCmp: String = sb.toString()

      var coinsGenerated = new ArrayList[String]()

      for (i <- start to (start + nrOfElements)) {
        var nonce = id + i
        var md = MessageDigest.getInstance("SHA-256")
        md.update(nonce.getBytes("UTF-8"))
        var digest = md.digest()
        var digestHexRep = bytes2hex(digest)

        if (digestHexRep <= hexToCmp) {
          //println(nonce + " : " + digestHexRep + " : " + digest.length)
          coinsGenerated.add(nonce + "\t" + digestHexRep)
        }
      }

      return coinsGenerated
    }

    /**
     * Function to convert Byte Array to Hex String
     */
    def bytes2hex(bytes: Array[Byte], sep: Option[String] = None): String = {
      sep match {
        case None => bytes.map("%02x".format(_)).mkString
        case _ => bytes.map("%02x".format(_)).mkString(sep.get)
      }
    }

    /**
     * Function to convert Hex String to Byte Array
     */
    def hex2bytes(hex: String): Array[Byte] = {
      hex.replaceAll("[^0-9A-Fa-f]", "").sliding(2, 2).toArray.map(Integer.parseInt(_, 16).toByte)
    }
  }
}