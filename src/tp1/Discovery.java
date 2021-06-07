package tp1;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.MulticastSocket;
import java.net.NetworkInterface;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.Map.Entry;
import java.util.logging.Logger;

import tp1.util.DiscoveryURI;

/**
 * <p>
 * A class to perform service discovery, based on periodic service contact
 * endpoint announcements over multicast communication.
 * </p>
 * 
 * <p>
 * Servers announce their *name* and contact *uri* at regular intervals. The
 * server actively collects received announcements.
 * </p>
 * 
 * <p>
 * Service announcements have the following format:
 * </p>
 * 
 * <p>
 * &lt;service-name-string&gt;&lt;delimiter-char&gt;&lt;service-uri-string&gt;
 * </p>
 */

public class Discovery {
	private static Logger Log = Logger.getLogger(Discovery.class.getName());
	private static Discovery instance = null;
	static {
		// addresses some multicast issues on some TCP/IP stacks
		System.setProperty("java.net.preferIPv4Stack", "true");
		// summarizes the logging format
		System.setProperty("java.util.logging.SimpleFormatter.format", "%4$s: %5$s");
	}

	// The pre-aggreed multicast endpoint assigned to perform discovery.
	static final InetSocketAddress DISCOVERY_ADDR = new InetSocketAddress("226.226.226.226", 2266);
	static final int DISCOVERY_PERIOD = 1000;
	static final int DISCOVERY_TIMEOUT = 5000;

	// Used separate the two fields that make up a service announcement.
	private static final String DELIMITER = "\t";

	private InetSocketAddress addr;
	private String serviceName;
	private String serviceURI;
	private Map<String, SortedSet<DiscoveryURI>> urismap = new HashMap<>();
	private boolean stopThreads;
	private String domain;

	private Discovery() {

	}

	public synchronized static Discovery getInstance() {
		if (instance == null)
			instance = new Discovery();
		return instance;
	}

	/**
	 * @param serviceName the name of the service to announce
	 * @param serviceURI  an uri string - representing the contact endpoint of the
	 *                    service being announced
	 */

	/**
	 * Starts sending service announcements at regular intervals...
	 */
	public void start(InetSocketAddress addr1, String domain1, String service1, String serviceURI1) {
		if (addr1 != null)
			this.addr = addr1;
		else
			this.addr = DISCOVERY_ADDR;
		this.domain = domain1;
		this.serviceName = String.format("%s:%s", domain, service1);
		this.serviceURI = serviceURI1;
		stopThreads = false;

		Log.info(String.format("Starting Discovery announcements on: %s for: %s -> %s", addr, serviceName, serviceURI));

		byte[] announceBytes = String.format("%s%s%s", serviceName, DELIMITER, serviceURI).getBytes();
		DatagramPacket announcePkt = new DatagramPacket(announceBytes, announceBytes.length, addr);

		try {
			MulticastSocket ms = new MulticastSocket(addr.getPort());
			ms.joinGroup(addr, NetworkInterface.getByInetAddress(InetAddress.getLocalHost()));
			// start thread to send periodic announcements
			new Thread(() -> {
				while (!stopThreads) {
					try {
						ms.send(announcePkt);
						Thread.sleep(DISCOVERY_PERIOD);
					} catch (Exception e) {
						e.printStackTrace();
						// do nothing
					}
				}
			}).start();
			// start thread to collect announcements
			new Thread(() -> {
				DatagramPacket pkt = new DatagramPacket(new byte[1024], 1024);
				while (!stopThreads) {
					try {
						pkt.setLength(1024);
						ms.receive(pkt);
						String msg = new String(pkt.getData(), 0, pkt.getLength());
						String[] msgElems = msg.split(DELIMITER);
						if (msgElems.length == 2) { // periodic announcement
							// System.out.printf( "FROM %s (%s) : %s\n",
							// pkt.getAddress().getCanonicalHostName(),
							// pkt.getAddress().getHostAddress(), msg);
							SortedSet<DiscoveryURI> elem = urismap.get(msgElems[0]);
							if (elem == null)
								elem = new TreeSet<>();
							//elem.add(URI.create((msgElems[1])));
							DiscoveryURI aux = new DiscoveryURI(msgElems[1], Instant.now().toString());
							if(elem.contains(aux))
								elem.remove(aux);
							elem.add(aux);
							urismap.put(msgElems[0], elem);
						}
					} catch (IOException e) {
						// do nothing
					}
				}
			}).start();
			new Thread(() -> {
				for (SortedSet<DiscoveryURI> set : urismap.values()) {
					@SuppressWarnings("unchecked")
					TreeSet<DiscoveryURI> copy = (TreeSet<DiscoveryURI>) ((TreeSet<DiscoveryURI>) set).clone();
					Iterator<DiscoveryURI> it = copy.iterator();
					boolean finish = false;
					while(it.hasNext() && !finish) {
						DiscoveryURI dUri = it.next();
						if(Instant.parse(dUri.getTimestamp()).isAfter(Instant.now().minusMillis(DISCOVERY_TIMEOUT)))
							set.remove(dUri);
					}
				}
			}
			).start();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	/**
	 * Returns the known servers for a service.
	 * 
	 * @param serviceName the name of the service being discovered
	 * @return an array of URI with the service instances discovered.
	 * 
	 */
	public DiscoveryURI[] knownUrisOf(String domain, String service) {
		String serviceName2 = String.format("%s:%s", domain, service);
		SortedSet<DiscoveryURI> set = urismap.get(serviceName2);
		if (set == null)
			return null;
		else
			return set.toArray(new DiscoveryURI[set.size()]);
	}

	public DiscoveryURI[] knownUrisOf(String service) {
		SortedSet<DiscoveryURI> set = new TreeSet<>();
		for (Entry<String, SortedSet<DiscoveryURI>> entry : urismap.entrySet()) {
			if (entry.getKey().contains(service)) {
				for (DiscoveryURI u : entry.getValue()) {
					set.add(u);
				}
			}
		}
		return set.toArray(new DiscoveryURI[set.size()]);
	}

	// Main just for testing purposes
	public static void main(String[] args) throws Exception {
		Discovery discovery = Discovery.getInstance();
		discovery.start(DISCOVERY_ADDR, "testDomain", "test", "http://" + InetAddress.getLocalHost().getHostAddress());
	}

	public void stop() {
		stopThreads = true;
	}
}
