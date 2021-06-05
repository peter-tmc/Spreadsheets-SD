package tp1.zookeeper.examples;

import java.util.Scanner;

import org.apache.zookeeper.CreateMode;

import tp1.zookeeper.ZookeeperProcessor;

public class Writer {
	// Main just for testing purposes
	public static void main( String[] args) throws Exception {
		Scanner sc = new Scanner(System.in);

		System.out.println("Provide a path (should start with /) :");
		String path = sc.nextLine().trim();

		System.out.println("Provide a value :");
		String value = sc.nextLine().trim();
		sc.close();

		ZookeeperProcessor zk = new ZookeeperProcessor( "localhost:2181,kafka:2181");
		String newPath = zk.write(path, CreateMode.PERSISTENT);

		if(newPath != null) {
			System.out.println("Created znode: " + newPath);
		}
		
		newPath = zk.write(path + "/bla_", value, CreateMode.EPHEMERAL_SEQUENTIAL);
		System.out.println("Created child znode: " + newPath);
		
	}

}
