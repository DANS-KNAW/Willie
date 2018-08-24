package com.noterik.springfield.willie.queue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Element;
import org.dom4j.Node;
import org.springfield.mojo.ftp.URIParser;
import org.springfield.mojo.interfaces.ServiceInterface;
import org.springfield.mojo.interfaces.ServiceManager;

import com.noterik.bart.marge.model.Service;
import com.noterik.bart.marge.server.MargeServer;
import com.noterik.springfield.willie.WillieServer;

import com.noterik.springfield.willie.homer.LazyHomer;
import com.noterik.springfield.willie.homer.LazyMarge;
import com.noterik.springfield.willie.homer.MargeObserver;
import com.noterik.springfield.willie.tools.TFHelper;

/**
 * Keeps the list of queues
 *
 * @author Derk Crezee <d.crezee@noterik.nl>
 * @author Daniel Ockeloen <daniel@noterik.nl>
 * @copyright Copyright: Noterik B.V. 2012
 * @package com.noterik.springfield.willie.queue
 * @access private
 * @version $Id: QueueManager.java,v 1.18 2012-07-31 19:06:09 daniel Exp $
 *
 */
public class QueueManager {
	/** The QueueManager's log4j Logger */
	public static final Logger LOG = Logger.getLogger(QueueManager.class);
	
	/** Queue URI template */
	public static final String QUEUE_URI = "/domain/{domain}/service/willie/queue";
	
	/** List of queues to watch */
	private List<Queue> queues;
	
	/**
	 * Default constructor.
	 */
	public QueueManager() {
		queues = new ArrayList<Queue>();
	}
	
	/**
	 * Add queue
	 * 
	 * @param queue
	 */
	public void add(Queue queue) {
		synchronized (queues) {
			queues.add(queue);
		}
	}
	
	/**
	 * Remove queue
	 * 
	 * @param queue
	 */
	public void remove(Queue queue) {
		synchronized (queues) {
			queues.remove(queue);
		}
	}
	
	/**
	 * Adds all queues of certain domain.
	 * 
	 * @param domain
	 */
	public boolean addDomain(String domain) {
		LOG.info("Adding domain " + domain);
		
		String uri = QUEUE_URI.replace("{domain}", domain);
		String xml = "<fsxml><properties><depth>1</depth></properties></fsxml>";
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return false;
		String response = smithers.get(uri,xml,"text/xml");
		
		LOG.debug("parsing response from smithers");
		try {
			// parse response
			Document doc = DocumentHelper.parseText(response);
			Element root = doc.getRootElement();
			Element elem;
			String id, priorityStr, queueUri;
			int priority;
			for(Iterator<Element> iter = root.elementIterator("queue"); iter.hasNext(); ) {
				elem = iter.next();
				id = elem.valueOf("@id");
				priorityStr = elem.valueOf("properties/priority");
				queueUri = uri + "/" + id;
				
				// parse priority
				priority = Queue.PRIORITY_DEFAULT;
				if(priorityStr!=null) {
					if(priorityStr.toLowerCase().equals("high")) {
						priority = Queue.PRIORITY_HIGH;
					}
					else if(priorityStr.toLowerCase().equals("medium")) {
						priority = Queue.PRIORITY_MEDIUM;
					}
					else if(priorityStr.toLowerCase().equals("low")) {
						priority = Queue.PRIORITY_LOW;
					}
				}
				
				// add new queue
				Queue queue = new Queue(queueUri,priority);
				this.add(queue);
				
				LOG.debug("added queue: " + queue.toString());
			}		
		} catch(Exception e) {
			LOG.error("Could not parse response from smithers",e);
			LOG.info("Could not parse response from smithers");
			return false;
		}
		
		return true;
	}
	
	/**
	 * Removes all queues of certain domain.
	 * 
	 * @param domain
	 */
	public void removeDomain(String domain) {		
		LOG.info("Removing domain "+domain);
		
		// loop trough all queues and remove if from given domain
		synchronized(queues) {
			Queue queue;
			String queueDomain;
			for(Iterator<Queue> iter = queues.iterator(); iter.hasNext(); ) {
				queue = iter.next();
				queueDomain = URIParser.getDomainFromUri(queue.getUri());
				if(domain.equals(queueDomain)) {
					iter.remove();
					LOG.debug("removed queue: " + queue);
				}
			}
		}
	}
	
	/**
	 * Get the first job from all the queues available.
	 * 
	 * @return The first job from all the queues available
	 */
	public Job getJob() {		
		LOG.debug("getting new job");
		
		synchronized (queues) {
			// sort queues (from high to low)
			Collections.sort(queues,Collections.reverseOrder());
			
			// iterate through queues
			for(Queue queue : queues) {
				List<Job> jobs = queue.getJobs();
				for(Job job : jobs) {
					if(job!=null) {
						LOG.debug("job found, checking");
						// check if job is good according to decision engine
						if(WillieServer.instance().getDecisionEngine().processJob(job)) {
							// check if 'useraw' has been set
							LOG.debug("Check if useraw has been set");
							String useraw = job.getProperty("useraw");
							if(useraw!=null) {
								LOG.debug("checking if following rawaudio is available "+useraw);
								
								// determine referid
								String referid = job.getProperty("referid");
								if(referid==null) {
									continue;
								}
								
								// determine the rawaudio
								String rawURI = URIParser.getPreviousUri(referid)+"/"+useraw;
								
								// check if raw is available
								if(!checkStatus(rawURI,"done")) {
									continue;
								}
							}
							return job;
						}
					}
				}
			}
		}		
		LOG.debug("no job found");
		
		// no job found
		return null;
	}

	/**
	 * Checks the status of a rawaudio
	 * 
	 * @param uri
	 * @param status
	 * @return
	 */	
	private boolean checkStatus(String uri, String status) {
		LOG.debug("checking status for "+uri+" - "+status);
		
		String domain = URIParser.getDomainFromUri(uri);
		
		// get service
		//MargeServer marge = MargeServer.getInstance(); TODO: fix marge caching
		MargeServer marge = new MargeServer();
		Service service = marge.getService("filesystemmanager", domain);
		if(service==null) {
			LOG.error("service was null");
			return false;
		}
		
		// request queues from filessystem
		String xml = "<fsxml><properties><depth>1</depth></properties></fsxml>";
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return false;
		String response = smithers.get(uri,xml,"text/xml");

		try {
			// parse response
			Document doc = DocumentHelper.parseText(response);
			Node node = doc.selectSingleNode("//properties/status");
			if(node == null || !node.getText().trim().toLowerCase().equals(status)) {
				return false;
			}
			
		} catch(Exception e) {
			LOG.error("Could not parse response from smithers",e);
		}
		return true;
	}
	
	public void destroy() {
		
	}
}
