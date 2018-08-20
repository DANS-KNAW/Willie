package com.noterik.springfield.willie.queue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.apache.log4j.Logger;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Node;
import org.springfield.mojo.ftp.URIParser;
import org.springfield.mojo.interfaces.ServiceInterface;
import org.springfield.mojo.interfaces.ServiceManager;

import com.noterik.bart.marge.model.Service;
import com.noterik.bart.marge.server.MargeServer;
import com.noterik.springfield.willie.homer.LazyHomer;

/**
 * Queue object
 *
 * @author Derk Crezee <d.crezee@noterik.nl>
 * @copyright Copyright: Noterik B.V. 2008
 * @package com.noterik.springfield.willie.queue
 * @access private
 * @version $Id: Queue.java,v 1.11 2012-07-31 19:06:09 daniel Exp $
 *
 */
public class Queue implements Comparable<Queue> {
	/** The Queue's log4j logger */
	private static final Logger LOG = Logger.getLogger(Queue.class);
	
	/** The queue's filesystem uri */
	private String uri;
	
	/** The queue's domain */
	private String domain;
	
	/** The queue's priority */
	private int priority;
	
	/** Some predefined priorities */
	public static final int PRIORITY_HIGH = 3;
	public static final int PRIORITY_MEDIUM = 2;
	public static final int PRIORITY_LOW = 1;
	public static final int PRIORITY_DEFAULT = PRIORITY_MEDIUM;
	
	/**
	 * Contructor.
	 * 
	 * @param uri			filesystem uri
	 */
	public Queue(String uri) {
		this(uri,PRIORITY_DEFAULT);
	}
	
	/**
	 * Constructor
	 * 
	 * @param uri			filesystem uri
	 * @param priority		queue priority
	 */
	public Queue(String uri, int priority) {
		this.uri = uri; 
		this.priority = priority;
		this.domain = URIParser.getDomainFromUri(uri);
	}
	
	/**
	 * Returns the queue's filesystem uri
	 * 
	 * @return The queue's filesystem uri
	 */
	public String getUri() {
		return uri;
	}
	
	/**
	 * Returns the queue's priority
	 * 
	 * @return The queue's priority
	 */
	public int getPriority() {
		return priority;
	}

	/**
	 * Compares the priorities of the queues
	 */
	public int compareTo(Queue q) throws ClassCastException {
		 Integer i1 = new Integer(priority);
		 Integer i2 = new Integer(q.getPriority());
		 return i1.compareTo(i2);
    }
	
	/**
	 * Compares uris
	 */
	public boolean equals(Queue q) {
		if(q!=null) {
			if(uri.equals(q.getUri())) {
				return true;
			}
		}
		return false;
	}
	
	/**
	 * Get all the jobs in this queue
	 * 
	 * @return The queue's next Job
	 */
	public List<Job> getJobs() {
		List<Job> jobs = new ArrayList<Job>();
		
		LOG.debug("getting jobs for queue: "+uri);

		// get jobs in queue
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return null;
		String queueXml = smithers.get(uri, null, null);
		
		LOG.debug("response: \n" + queueXml);
		// parse document
		try {
			Document queueDoc = DocumentHelper.parseText(queueXml);
		
			// iterate through jobs
			List<Node> nodeList = queueDoc.selectNodes("//queue/job");
			Node node;
			String jobMount, jobUri;
			for(Iterator<Node> iter2 = nodeList.iterator(); iter2.hasNext(); ) {
				node = iter2.next();
				
				// parse to job
				jobUri = uri + "/job/" + node.valueOf("@id");
				
				// add to job list
				//log.debug("JOB="+node.asXML());
				Job job = new Job(jobUri,node.asXML());
				if (job.isValidJob()) {
					jobs.add(job);
				} else {
					//remove invalid job
					removeJob(job);
				}
			}
			
		} catch (DocumentException e) {
			LOG.error("Response from filesystem could not be parsed",e);
		}
		
		return jobs;
	}
	
	@Override
	public String toString() {
		return "("+uri+","+priority+")";
	}
	
	/**
	 * Removes job from queue 
	 * 
	 * @param job
	 */
	public void removeJob(Job job) {
		LOG.debug("removing job: "+job);
		
		// send delete call
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return;
		smithers.delete( job.getUri(), null, null);
		LOG.debug("send delete call to "+job.getUri());
	}
}
