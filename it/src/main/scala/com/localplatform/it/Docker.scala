package com.localplatform.it

import java.io.{FileOutputStream, IOException}
import java.net.{InetAddress, InetSocketAddress, URL}
import java.nio.file.{Files, Path, Paths}
import java.util.Collections._
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.{Properties, List => JList, Map => JMap}

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.dataformat.javaprop.JavaPropsMapper
import com.google.common.collect.ImmutableMap
import com.google.common.primitives.Ints._
import com.spotify.docker.client.messages.EndpointConfig.EndpointIpamConfig
import com.spotify.docker.client.messages._
import com.spotify.docker.client.{DefaultDockerClient, DockerClient}
import com.typesafe.config.ConfigFactory._
import com.typesafe.config.{Config, ConfigRenderOptions}
import com.localplatform.it.api.AsyncHttpApi._
import com.localplatform.it.util.GlobalTimer.{instance => timer}
import com.localplatform.settings._
import com.localplatform.state.EitherExt2
import monix.eval.Coeval
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import org.apache.commons.compress.archivers.ArchiveStreamFactory
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.io.IOUtils
import org.asynchttpclient.Dsl._
import com.localplatform.account.AddressScheme
import com.localplatform.utils.ScorexLogging
import com.localplatform.block.Block

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, blocking}
import scala.util.control.NonFatal
import scala.util.{Random, Try}

class Docker(suiteConfig: Config = empty, tag: String = "", enableProfiling: Boolean = false) extends AutoCloseable with ScorexLogging {

  import Docker._

  private val http = asyncHttpClient(
    config()
      .setNettyTimer(timer)
      .setMaxConnections(18)
      .setMaxConnectionsPerHost(3)
      .setMaxRequestRetry(1)
      .setReadTimeout(10000)
      .setKeepAlive(false)
      .setRequestTimeout(10000))

  private val client = DefaultDockerClient.fromEnv().build()

  private val nodes     = ConcurrentHashMap.newKeySet[DockerNode]()
  private val isStopped = new AtomicBoolean(false)

  dumpContainers(client.listContainers())
  sys.addShutdownHook {
    log.debug("Shutdown hook")
    close()
  }

  private[it] val configTemplate = parseResources("template.conf")

  AddressScheme.current = new AddressScheme {
    override val chainId = configTemplate.as[String]("local.blockchain.custom.address-scheme-character").charAt(0).toByte
  }

  private[it] val genesisOverride = {
    val genesisTs          = System.currentTimeMillis()
    val timestampOverrides = parseString(s"""local.blockchain.custom.genesis {
         |  timestamp = $genesisTs
         |  block-timestamp = $genesisTs
         |}""".stripMargin)

    val genesisConfig    = configTemplate.withFallback(timestampOverrides)
    val gs               = genesisConfig.as[GenesisSettings]("local.blockchain.custom.genesis")
    val genesisSignature = Block.genesis(gs).explicitGet().uniqueId

    timestampOverrides.withFallback(parseString(s"local.blockchain.custom.genesis.signature = $genesisSignature"))
  }

  // a random network in 10.x.x.x range
  private val networkSeed = Random.nextInt(0x100000) << 4 | 0x0A000000
  // 10.x.x.x/28 network will accommodate up to 13 nodes
  private val networkPrefix = s"${InetAddress.getByAddress(toByteArray(networkSeed)).getHostAddress}/28"

  private val logDir: Coeval[Path] = Coeval.evalOnce {
    val r = Option(System.getProperty("local.it.logging.dir"))
      .map(Paths.get(_))
      .getOrElse(Paths.get(System.getProperty("user.dir"), "target", "logs"))

    Files.createDirectories(r)
    r
  }

  private def ipForNode(nodeId: Int) = InetAddress.getByAddress(toByteArray(nodeId & 0xF | networkSeed)).getHostAddress

  private lazy val localNetwork: Network = {
    val networkName = s"local-${hashCode().toLong.toHexString}"

    def network: Option[Network] =
      try {
        val networks = client.listNetworks(DockerClient.ListNetworksParam.byNetworkName(networkName))
        if (networks.isEmpty) None else Some(networks.get(0))
      } catch {
        case NonFatal(_) => network
      }

    def attempt(rest: Int): Network =
      try {
        network match {
          case Some(n) =>
            val ipam = s"ipam: ${n
              .ipam()
              .config()
              .asScala
              .map { n =>
                s"subnet=${n.subnet()}, ip range=${n.ipRange()}"
              }
              .mkString(", ")}"
            log.info(s"Network ${n.name()} (id: ${n.id()}) is created for $tag, $ipam")
            n
          case None =>
            log.debug(s"Creating network $networkName for $tag")
            // Specify the network manually because of race conditions: https://github.com/moby/moby/issues/20648
            val r = client.createNetwork(
              NetworkConfig
                .builder()
                .name(networkName)
                .ipam(
                  Ipam
                    .builder()
                    .driver("default")
                    .config(singletonList(IpamConfig.create(networkPrefix, networkPrefix, ipForNode(0xE))))
                    .build()
                )
                .checkDuplicate(true)
                .build())
            Option(r.warnings()).foreach(log.warn(_))
            attempt(rest - 1)
        }
      } catch {
        case NonFatal(e) =>
          log.warn(s"Can not create a network for $tag", e)
          if (rest == 0) throw e else attempt(rest - 1)
      }

    attempt(5)
  }

  def startNodes(nodeConfigs: Seq[Config]): Seq[DockerNode] = {
    log.trace(s"Starting ${nodeConfigs.size} containers")
    val all = nodeConfigs.map(startNodeInternal)
    Await.result(
      for {
        _ <- Future.traverse(all)(_.waitForStartup())
        _ <- Future.traverse(all)(connectToAll)
      } yield (),
      5.minutes
    )
    all
  }

  def startNode(nodeConfig: Config, autoConnect: Boolean = true): DockerNode = {
    val node = startNodeInternal(nodeConfig)
    Await.result(
      node.waitForStartup().flatMap(_ => if (autoConnect) connectToAll(node) else Future.successful(())),
      3.minutes
    )
    node
  }

  private def connectToAll(node: DockerNode): Future[Unit] = {
    def connectToOne(address: InetSocketAddress): Future[Unit] = {
      for {
        _              <- node.connect(address)
        _              <- Future(blocking(Thread.sleep(1.seconds.toMillis)))
        connectedPeers <- node.connectedPeers
        _ <- {
          val connectedAddresses = connectedPeers.map(_.address.replaceAll("""^.*/([\d\.]+).+$""", "$1")).sorted
          log.debug(s"Looking for ${address.getHostName} in $connectedAddresses")
          if (connectedAddresses.contains(address.getHostName)) Future.successful(())
          else {
            log.debug(s"Not found ${address.getHostName}, retrying")
            connectToOne(address)
          }
        }
      } yield ()
    }

    val seedAddresses = nodes.asScala
      .filterNot(_.name == node.name)
      .filterNot { node =>
        // Exclude disconnected
        client.inspectContainer(node.containerId).networkSettings().networks().isEmpty
      }
      .map(_.containerNetworkAddress)

    if (seedAddresses.isEmpty) Future.successful(())
    else
      Future
        .traverse(seedAddresses)(connectToOne)
        .map(_ => ())
  }

  private def startNodeInternal(nodeConfig: Config): DockerNode =
    try {
      val overrides = nodeConfig
        .withFallback(suiteConfig)
        .withFallback(genesisOverride)

      val actualConfig = overrides
        .withFallback(configTemplate)
        .withFallback(defaultApplication())
        .withFallback(defaultReference())
        .resolve()

      val restApiPort    = actualConfig.getString("local.rest-api.port")
      val networkPort    = actualConfig.getString("local.network.port")
      val matcherApiPort = actualConfig.getString("local.matcher.port")

      val portBindings = new ImmutableMap.Builder[String, java.util.List[PortBinding]]()
        .put(s"$ProfilerPort", singletonList(PortBinding.randomPort("0.0.0.0")))
        .put(restApiPort, singletonList(PortBinding.randomPort("0.0.0.0")))
        .put(networkPort, singletonList(PortBinding.randomPort("0.0.0.0")))
        .put(matcherApiPort, singletonList(PortBinding.randomPort("0.0.0.0")))
        .build()

      val hostConfig = HostConfig
        .builder()
        .portBindings(portBindings)
        .build()

      val nodeName   = actualConfig.getString("local.network.node-name")
      val nodeNumber = nodeName.replace("node", "").toInt
      val ip         = ipForNode(nodeNumber)

      val javaOptions = Option(System.getenv("CONTAINER_JAVA_OPTS")).getOrElse("")
      val configOverrides: String = {
        var config = s"$javaOptions ${renderProperties(asProperties(overrides))} " +
          s"-Dlogback.stdout.level=TRACE -Dlogback.file.level=OFF -Dlocal.network.declared-address=$ip:$networkPort "

        if (enableProfiling) {
          config += s"-agentpath:/usr/local/YourKit-JavaProfiler-2018.04/bin/linux-x86-64/libyjpagent.so=port=$ProfilerPort,listen=all," +
            s"sampling,monitors,sessionname=LocalNode,dir=$ContainerRoot/profiler,logdir=$ContainerRoot "
        }

        val withAspectJ = Option(System.getenv("WITH_ASPECTJ")).fold(false)(_.toBoolean)
        if (withAspectJ) config += s"-javaagent:$ContainerRoot/aspectjweaver.jar "
        config
      }

      val containerConfig = ContainerConfig
        .builder()
        .image("com.localplatform/it:latest")
        .exposedPorts(s"$ProfilerPort", restApiPort, networkPort, matcherApiPort)
        .networkingConfig(ContainerConfig.NetworkingConfig.create(Map(
          localNetwork.name() -> endpointConfigFor(nodeName)
        ).asJava))
        .hostConfig(hostConfig)
        .env(s"LOCAL_OPTS=$configOverrides")
        .build()

      val containerId = {
        val containerName = s"${localNetwork.name()}-$nodeName"
        dumpContainers(
          client.listContainers(DockerClient.ListContainersParam.filter("name", containerName)),
          "Containers with same name"
        )

        log.debug(s"Creating container $containerName at $ip with options: $javaOptions")
        val r = client.createContainer(containerConfig, containerName)
        Option(r.warnings().asScala).toSeq.flatten.foreach(log.warn(_))
        r.id()
      }

      client.startContainer(containerId)

      val node = new DockerNode(actualConfig, containerId, getNodeInfo(containerId, LocalSettings.fromConfig(actualConfig)))
      nodes.add(node)
      log.debug(s"Started $containerId -> ${node.name}: ${node.nodeInfo}")
      node
    } catch {
      case NonFatal(e) =>
        log.error("Can't start a container", e)
        dumpContainers(client.listContainers())
        throw e
    }

  private def getNodeInfo(containerId: String, settings: LocalSettings): NodeInfo = {
    val restApiPort    = settings.restAPISettings.port
    val matcherApiPort = settings.matcherSettings.port
    val networkPort    = settings.networkSettings.bindAddress.getPort

    val containerInfo = inspectContainer(containerId)
    val ports         = containerInfo.networkSettings().ports()

    val localIpAddress = containerInfo.networkSettings().networks().get(localNetwork.name()).ipAddress()

    NodeInfo(
      new URL(s"http://localhost:${extractHostPort(ports, restApiPort)}"),
      new URL(s"http://localhost:${extractHostPort(ports, matcherApiPort)}"),
      new InetSocketAddress("localhost", extractHostPort(ports, networkPort)),
      new InetSocketAddress(localIpAddress, networkPort)
    )
  }

  private def inspectContainer(containerId: String): ContainerInfo = {
    val containerInfo = client.inspectContainer(containerId)
    if (containerInfo.networkSettings().networks().asScala.contains(localNetwork.name())) containerInfo
    else {
      log.debug(s"Container $containerId has not connected to the network ${localNetwork.name()} yet, retry")
      Thread.sleep(1000)
      inspectContainer(containerId)
    }
  }

  override def close(): Unit = {
    if (isStopped.compareAndSet(false, true)) {
      log.info("Stopping containers")

      nodes.asScala.foreach { node =>
        takeProfileSnapshot(node)
        client.stopContainer(node.containerId, 0)

        saveProfile(node)
        saveLog(node)
        val containerInfo = client.inspectContainer(node.containerId)
        log.debug(s"""Container information for ${node.name}:
             |Exit code: ${containerInfo.state().exitCode()}
             |Error: ${containerInfo.state().error()}
             |Status: ${containerInfo.state().status()}
             |OOM killed: ${containerInfo.state().oomKilled()}""".stripMargin)

        try {
          client.removeContainer(node.containerId)
        } catch {
          case NonFatal(e) => log.warn(s"Can't remove a container of ${node.name}", e)
        }
      }

      try {
        client.removeNetwork(localNetwork.id)
      } catch {
        case NonFatal(e) =>
          // https://github.com/moby/moby/issues/17217
          log.warn(s"Can not remove network ${localNetwork.name()}", e)
      }

      http.close()
      client.close()
    }
  }

  private def saveLog(node: DockerNode): Unit = {
    val containerId = node.containerId
    val logFile     = logDir().resolve(s"${node.name}.log").toFile
    log.info(s"Writing logs of $containerId to ${logFile.getAbsolutePath}")

    val fileStream = new FileOutputStream(logFile, false)
    try {
      client
        .logs(
          containerId,
          DockerClient.LogsParam.follow(),
          DockerClient.LogsParam.stdout(),
          DockerClient.LogsParam.stderr()
        )
        .attach(fileStream, fileStream)
    } finally {
      fileStream.close()
    }
  }

  private def takeProfileSnapshot(node: DockerNode): Unit = if (enableProfiling) {
    val task = client.execCreate(
      node.containerId,
      Array(
        "java",
        "-jar",
        ProfilerController.toString,
        "127.0.0.1",
        ProfilerPort.toString,
        "capture-performance-snapshot"
      ),
      DockerClient.ExecCreateParam.attachStdout(),
      DockerClient.ExecCreateParam.attachStderr()
    )
    Option(task.warnings()).toSeq.flatMap(_.asScala).foreach(log.warn(_))
    client.execStart(task.id())
    while (client.execInspect(task.id()).running()) {
      log.trace(s"Snapshot of ${node.name} has not been took yet, wait...")
      blocking(Thread.sleep(1000))
    }
  }

  private def saveProfile(node: DockerNode): Unit = if (enableProfiling) {
    try {
      val profilerDirStream = client.archiveContainer(node.containerId, ContainerRoot.resolve("profiler").toString)

      try {
        val archiveStream = new ArchiveStreamFactory().createArchiveInputStream(ArchiveStreamFactory.TAR, profilerDirStream)
        val snapshotFile = Iterator
          .continually(Option(archiveStream.getNextEntry))
          .takeWhile(_.nonEmpty)
          .collectFirst {
            case Some(entry: TarArchiveEntry) if entry.isFile && entry.getName.contains(".snapshot") => entry
          }

        snapshotFile.foreach { archiveFile =>
          val output = new FileOutputStream(logDir().resolve(s"${node.name}.snapshot").toFile)
          try {
            IOUtils.copy(archiveStream, output)
            log.info(s"The snapshot of ${node.name} was successfully saved")
          } catch {
            case e: Throwable => throw new IOException(s"Can't copy ${archiveFile.getName} of ${node.name} to local fs", e)
          } finally {
            output.close()
          }
        }
      } catch {
        case e: Throwable => throw new IOException(s"Can't read a profiler directory stream of ${node.name}", e)
      } finally {
        // Some kind of https://github.com/spotify/docker-client/issues/745
        // But we have to close this stream, otherwise the thread will be blocked
        Try(profilerDirStream.close())
      }
    } catch {
      case e: Throwable => log.warn(s"Can't save profiler logs of ${node.name}", e)
    }
  }

  def disconnectFromNetwork(node: DockerNode): Unit = disconnectFromNetwork(node.containerId)

  private def disconnectFromNetwork(containerId: String): Unit = client.disconnectFromNetwork(containerId, localNetwork.id())

  def restartContainer(node: DockerNode): DockerNode = {
    val id            = node.containerId
    val containerInfo = inspectContainer(id)
    val ports         = containerInfo.networkSettings().ports()
    log.info(s"New ports: ${ports.toString}")
    client.restartContainer(id, 10)

    node.nodeInfo = getNodeInfo(node.containerId, node.settings)
    Await.result(
      node.waitForStartup().flatMap(_ => connectToAll(node)),
      3.minutes
    )
    node
  }

  def connectToNetwork(nodes: Seq[DockerNode]): Unit = {
    nodes.foreach(connectToNetwork)
    Await.result(Future.traverse(nodes)(connectToAll), 1.minute)
  }

  private def connectToNetwork(node: DockerNode): Unit = {
    client.connectToNetwork(
      localNetwork.id(),
      NetworkConnection
        .builder()
        .containerId(node.containerId)
        .endpointConfig(endpointConfigFor(node.name))
        .build()
    )

    node.nodeInfo = getNodeInfo(node.containerId, node.settings)
    log.debug(s"New ${node.name} settings: ${node.nodeInfo}")
  }

  private def endpointConfigFor(nodeName: String): EndpointConfig = {
    val nodeNumber = nodeName.replace("node", "").toInt
    val ip         = ipForNode(nodeNumber)

    EndpointConfig
      .builder()
      .ipAddress(ip)
      .ipamConfig(EndpointIpamConfig.builder().ipv4Address(ip).build())
      .build()
  }

  private def dumpContainers(containers: java.util.List[Container], label: String = "Containers"): Unit = {
    val x =
      if (containers.isEmpty) "No"
      else
        "\n" + containers.asScala
          .map { x =>
            s"Container(${x.id()}, status: ${x.status()}, names: ${x.names().asScala.mkString(", ")})"
          }
          .mkString("\n")

    log.debug(s"$label: $x")
  }
}

object Docker {
  private val ContainerRoot      = Paths.get("/opt/local")
  private val ProfilerController = ContainerRoot.resolve("yjp-controller-api-redist.jar")
  private val ProfilerPort       = 10001
  private val jsonMapper         = new ObjectMapper
  private val propsMapper        = new JavaPropsMapper

  def apply(owner: Class[_]): Docker = new Docker(tag = owner.getSimpleName)

  private def asProperties(config: Config): Properties = {
    val jsonConfig = config.root().render(ConfigRenderOptions.concise())
    propsMapper.writeValueAsProperties(jsonMapper.readTree(jsonConfig))
  }

  private def renderProperties(p: Properties) =
    p.asScala
      .map {
        case (k, v) if v.contains(" ") => k -> s""""$v""""
        case x                         => x
      }
      .map { case (k, v) => s"-D$k=$v" }
      .mkString(" ")

  private def extractHostPort(m: JMap[String, JList[PortBinding]], containerPort: Int) =
    m.get(s"$containerPort/tcp").get(0).hostPort().toInt

  case class NodeInfo(nodeApiEndpoint: URL,
                      matcherApiEndpoint: URL,
                      hostNetworkAddress: InetSocketAddress,
                      containerNetworkAddress: InetSocketAddress)

  class DockerNode(config: Config, val containerId: String, private[Docker] var nodeInfo: NodeInfo) extends Node(config) {
    override def nodeApiEndpoint: URL = nodeInfo.nodeApiEndpoint

    override def matcherApiEndpoint: URL = nodeInfo.matcherApiEndpoint

    override val apiKey = "integration-test-rest-api"

    override def networkAddress: InetSocketAddress = nodeInfo.hostNetworkAddress

    def containerNetworkAddress: InetSocketAddress = nodeInfo.containerNetworkAddress

    def getConfig: Config = config
  }

}
