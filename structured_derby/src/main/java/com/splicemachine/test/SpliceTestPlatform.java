package com.splicemachine.test;

import java.io.File;
import java.util.Arrays;

import com.splicemachine.derby.impl.job.coprocessor.CoprocessorTaskScheduler;
import com.splicemachine.si.coprocessors.SIObserver;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseConfiguration;
import org.apache.hadoop.hbase.MiniHBaseCluster;
import com.splicemachine.derby.hbase.SpliceDerbyCoprocessor;
import com.splicemachine.derby.hbase.SpliceIndexEndpoint;
import com.splicemachine.derby.hbase.SpliceIndexManagementEndpoint;
import com.splicemachine.derby.hbase.SpliceIndexObserver;
import com.splicemachine.derby.hbase.SpliceMasterObserver;
import com.splicemachine.derby.hbase.SpliceOperationCoprocessor;
import com.splicemachine.derby.hbase.SpliceOperationRegionObserver;

public class SpliceTestPlatform extends TestConstants {
	protected MiniZooKeeperCluster miniZooKeeperCluster;
	protected MiniHBaseCluster miniHBaseCluster;
	protected MiniHBaseCluster miniHBaseCluster2;
	protected String zookeeperTargetDirectory;
	protected String hbaseTargetDirectory;
    protected Integer masterPort;
    protected Integer masterInfoPort;
    protected Integer regionServerPort;
    protected Integer regionServerInfoPort;

    public SpliceTestPlatform() {
		super();
	}
	
	public SpliceTestPlatform(String targetDirectory) {
		this(targetDirectory + "zookeeper",targetDirectory + "hbase");
	}

    public SpliceTestPlatform(String zookeeperTargetDirectory, String hbaseTargetDirectory) {
        this(zookeeperTargetDirectory, hbaseTargetDirectory, null, null, null, null);
    }


	public SpliceTestPlatform(String zookeeperTargetDirectory, String hbaseTargetDirectory, Integer masterPort, Integer masterInfoPort, Integer regionServerPort, Integer regionServerInfoPort) {
		this.zookeeperTargetDirectory = zookeeperTargetDirectory;
		this.hbaseTargetDirectory = hbaseTargetDirectory;

        this.masterPort = masterPort;
        this.masterInfoPort = masterInfoPort;
        this.regionServerPort = regionServerPort;
        this.regionServerInfoPort = regionServerInfoPort;
	}

	public static void main(String[] args) throws Exception {
		SpliceTestPlatform spliceTestPlatform;
		if (args.length == 1) {
			spliceTestPlatform = new SpliceTestPlatform(args[0]);
            spliceTestPlatform.start();
		}else if (args.length == 2) {
			spliceTestPlatform = new SpliceTestPlatform(args[0],args[1]);
			spliceTestPlatform.start();
		}else if (args.length == 6) {
            spliceTestPlatform = new SpliceTestPlatform(args[0], args[1], new Integer(args[2]), new Integer(args[3]), new Integer(args[4]), new Integer(args[5]));
            spliceTestPlatform.start();

        }else{
			System.out.println("Splice Test Platform supports one argument providing the target directory" +
					" or two arguments dictating the zookeeper and hbase directory.");
			System.exit(1);
		}
	}
	
	public void start() throws Exception {
		Configuration config = HBaseConfiguration.create();
		setBaselineConfigurationParameters(config);
		miniZooKeeperCluster = new MiniZooKeeperCluster();
		miniZooKeeperCluster.startup(new File(zookeeperTargetDirectory),3);
		miniHBaseCluster = new MiniHBaseCluster(config,1,1);
	}
	public void end() throws Exception {

	}

    private void setInt(Configuration configuration, String property, Integer intProperty){
        if(intProperty != null){
            configuration.setInt(property, intProperty.intValue());
        }
    }

	public void setBaselineConfigurationParameters(Configuration configuration) {
		configuration.set("hbase.rootdir", "file://" + hbaseTargetDirectory);
		configuration.set("hbase.rpc.timeout", "6000");
		configuration.set("hbase.cluster.distributed", "true");
		configuration.setInt("hbase.balancer.period", 10000);
		configuration.set("hbase.zookeeper.quorum", "127.0.0.1:2181,127.0.0.1:2182,127.0.0.1:2183");
		configuration.set("hbase.regionserver.handler.count", "40");

        setInt(configuration, "hbase.master.port", masterPort);
        setInt(configuration, "hbase.master.info.port", masterInfoPort);

        setInt(configuration, "hbase.regionserver.port", regionServerPort);
        setInt(configuration, "hbase.regionserver.info.port", regionServerInfoPort);

        coprocessorBaseline(configuration);
		configuration.reloadConfiguration();
	}

    public void coprocessorBaseline(Configuration configuration) {
        configuration.set("hbase.coprocessor.region.classes",
                SpliceOperationRegionObserver.class.getCanonicalName() + "," +
                        SpliceOperationCoprocessor.class.getCanonicalName() + "," +
                        SpliceIndexObserver.class.getCanonicalName() + "," +
                        SpliceDerbyCoprocessor.class.getCanonicalName() + "," +
                        SpliceIndexManagementEndpoint.class.getCanonicalName() + "," +
                        SpliceIndexEndpoint.class.getCanonicalName() + "," +
                        CoprocessorTaskScheduler.class.getCanonicalName()+","+
                        SIObserver.class.getCanonicalName()
        );
        configuration.set("hbase.coprocessor.master.classes", SpliceMasterObserver.class.getCanonicalName() + "");
    }

}
