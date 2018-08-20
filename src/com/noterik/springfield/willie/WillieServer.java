package com.noterik.springfield.willie;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.dom4j.Document;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.springfield.mojo.interfaces.ServiceInterface;
import org.springfield.mojo.interfaces.ServiceManager;

import com.noterik.springfield.willie.TF.TranscoderWorker;
import com.noterik.springfield.willie.homer.LazyHomer;
import com.noterik.springfield.willie.homer.WillieProperties;
import com.noterik.springfield.willie.queue.Job;
import com.noterik.springfield.willie.queue.QueueManager;
import com.noterik.springfield.willie.queue.dist.DecisionEngine;
import com.noterik.springfield.willie.queue.dist.SimpleDecisionEngine;

/**
 * Main willie class
 *
 */
public class WillieServer {
	/** The WillieServer's log4j Logger */
	private static Logger log = Logger.getLogger(WillieServer.class);

	/** base uri of the file system */
	private static String DOMAIN_URI = "/domain";	

	/** service type of this service */
	private static final String SERVICE_TYPE = "transcodingservice";
	
	/** Default decission engine */
	private static DecisionEngine DEFAULT_DECISION_ENGINE = new SimpleDecisionEngine();
	
	/** instance */
	private static WillieServer instance = new WillieServer();
	
	/** Root path: the path the webservice is running in */
	private String rootPath;
	private String scriptsPath;
	
	/** The queue manager */
	private QueueManager qm;
	
	/** An array of transcoder threads */
	private TranscoderWorker[] workers;
	
	/** Decision Engine, which determines which jobs should be picked up by this willie */
	private DecisionEngine dEngine;
	
	/** configuration properties */
	private Properties configuration;
	
	private Boolean running =  false;
	
	/**
	 * Sole constructor
	 */
	public WillieServer() {
		instance = this;
	}
	
	/**
	 * Return WillieConfiguration instance
	 * 
	 * @return WillieConfiguration instance.
	 */
	public static WillieServer instance() {
		return instance;
	}

	
	public Boolean isRunning() {
		if (running) {
			return true;
		}
		return false;
	}
	
	/**
	 * Return the queue manager
	 * 
	 * @return The queue manager
	 */
	public QueueManager getQueueManager() {
		return qm;
	}
	
	/**
	 * Returns the decision engine.
	 * 
	 * @return The decision engine.
	 */
	public DecisionEngine getDecisionEngine() {
		return dEngine;
	}
	
	/**
	 * Returns the configuration.
	 * 
	 * @return The configuration.
	 */
	public Properties getConfiguration() {
		return configuration;
	}
	
	/**
	 * Set root path
	 */
	public void setRootPath(String rootPath) {
		this.rootPath = rootPath;
		this.scriptsPath = rootPath + "/scripts/";
		//init();
	}

	
	/**
	 * Initializes the configuration
	 */
	public void init() {
		Thread t = new Thread() {
			public void run() {
				// init properties xml
				initConfigurationXML();
		
				// read configuration for decision engine
				initDecisionEngine();
		
				// init queue manager
				initQueueManager();
		
				// start 
				initWorkers();
			}
		};
		t.start();
		
		running = true;
	}
	
	/**
	 * Loads configuration file.
	 */
	private void initConfigurationXML() {
		log.debug("Initializing configuration file.");
		
		// configuration file
		configuration = new Properties();
		
		WillieProperties mp = LazyHomer.getMyWillieProperties();
		if (mp!=null) {
			configuration.put("decision-engine", mp.getDecisionEngine());
			configuration.put("number-of-workers", mp.getNumberOfWorkers());
			configuration.put("default-log-level", mp.getDefaultLogLevel());
			configuration.put("ffmpeg-path", mp.getFFMPEGPath());
			configuration.put("temporary-directory", mp.getTemporaryDirectory());
			configuration.put("batchFilesPath", mp.getBatchFilesPath());
			configuration.put("batchFilesExtension", mp.getBatchFilesExtension());
		} else {
			log.debug("Loading from configuration failed.");
		}
	}

	/**
	 * Reads configuration to determine the decision engine to use
	 */
	private void initDecisionEngine() {
		log.info("Initializing decesion engine.");
		
		// get class name from configuration
		String className = configuration.getProperty("decision-engine");
		
		// try loading decision engine
		try {
			Class clazz = Class.forName(className);
			dEngine = (DecisionEngine) clazz.newInstance();
			log.info("decision engine: " + dEngine.getClass().getName());
		} catch (Exception e) {
			log.error("Could not load class from configuration, switching to default.");
			dEngine = DEFAULT_DECISION_ENGINE;
		}
		log.info("Initializing decesion engine done.");
	}
	
	
	/**
	 * Returns a list of domains that this Willie is serving
	 * 
	 * @return
	 */
	public List<String> getOwnDomains() {
			List<String> domains = new ArrayList<String>();
			domains.add("webtv");
			return domains;
	}
	

	/**
	 * Initializes the queue manager
	 */
	private void initQueueManager() {
		log.info("Initializing queuemanager.");
		
		// create new queue manager
		qm = new QueueManager();
		
		String uri = DOMAIN_URI;
		String xml = "<fsxml><properties><depth>1</depth></properties></fsxml>";
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return;
		String response = smithers.get(uri,xml,"text/xml");
		
		try {
			// parse response
			Document doc = DocumentHelper.parseText(response);
			Element root = doc.getRootElement();
			Element elem;;
			for(Iterator<Element> iter = root.elementIterator("domain"); iter.hasNext(); ) {
				elem = iter.next();
				String id = elem.valueOf("@id");
				qm.addDomain(id);
			}
		} catch(Exception e) {
			log.error("Could not retrieve domains.");
		}
		log.info("Initializing queuemanager done.");
	}
	
	/**
	 * Initializes the transcoder workers.
	 */
	private void initWorkers() {
		log.info("Initializing workers.");
		
		int numberOfWorkers = 1;
		
		try {
			numberOfWorkers = Integer.parseInt(configuration.getProperty("number-of-workers"));
		} catch(Exception e) {
			log.debug("Could not load number of worker threads from configuration, switching to default (1).");
		}		
		log.info("number of workers: " + numberOfWorkers);
		
		// create workers array
		workers = new TranscoderWorker[numberOfWorkers];

		// setup new workers and start them
		for(int i=0; i<numberOfWorkers; i++) {
			workers[i] = new TranscoderWorker();
			workers[i].init();
		}
		
		log.info("Initializing workers done.");
	}
	
    
    /**
     * Checks if the workers are currently processing this job
     */
    public boolean runningJob(Job job) {
    	for(TranscoderWorker worker : workers) {
    		if(job.equals(worker.getCurrentJob())) {
    			return true;
    		}
    	}
    	return false;
    }

    /**
     * Shutdown
     */
	public void destroy() {
		qm.destroy();
		instance = null;
		running = false;
	}
}
