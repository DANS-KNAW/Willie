package com.noterik.springfield.willie.TF;

import java.io.File;

import org.apache.log4j.Logger;
import org.springfield.mojo.interfaces.ServiceInterface;
import org.springfield.mojo.interfaces.ServiceManager;

import com.noterik.bart.marge.model.Service;
import com.noterik.bart.marge.server.MargeServer;
import com.noterik.springfield.willie.WillieServer;
import com.noterik.springfield.willie.commandrunner.CommandRunner;
import com.noterik.springfield.willie.homer.LazyHomer;
import com.noterik.springfield.willie.homer.LazyMarge;
import com.noterik.springfield.willie.homer.MargeObserver;
import com.noterik.springfield.willie.homer.MountProperties;
import com.noterik.springfield.willie.queue.Job;
import com.noterik.springfield.willie.queue.QueueManager;

/**
 * Worker thread that picks up jobs.
 *
 * @author Derk Crezee <d.crezee@noterik.nl>
 * @author Daniel Ockeloen <daniel@noterik.nl>
 * @copyright Copyright: Noterik B.V. 2012
 * @package com.noterik.springfield.willie.TF
 * @access private
 * @version $Id: TranscoderWorker.java,v 1.30 2012-07-31 19:06:36 daniel Exp $
 *
 */
public class TranscoderWorker implements MargeObserver {
	/**	the TranscoderWorker's log4j logger */
	private static final Logger log = Logger.getLogger(TranscoderWorker.class);
	
	private boolean busy = false;
	
	/**
	 * Current executing job
	 */
	private Job cJob = null;
	
	public void init() {
		log.info("Starting worker");
		
		// subscribe to changed on the queue's
				LazyMarge.addObserver("/domain/webtv/service/willie/queue", this);
				LazyMarge.addTimedObserver("/domain/webtv/service/willie/queue",6,this);
	}
	
	public synchronized boolean checkForNewJob() {
		// get next job
		busy = true;
		
		QueueManager qm = WillieServer.instance().getQueueManager();
		if (qm==null) {
			log.info("TranscoderWorker : Queuemanager not found");
			busy = false;
			return false;
		}
		cJob = qm.getJob();
		if(cJob!=null) {
			//log.debug("TR="+cJob.getStatusProperty("trancoder"));
			if (cJob.getStatusProperty("trancoder")==null) { // no transcoder
				
				log.debug("got new job: "+cJob);

				// transcode job
				boolean success = transcode();
				log.debug("finished transcoding successfully: "+Boolean.toString(success));
			
				// call to job finished
				jobFinished(success);
			
				// remove job
				removeJob();
				busy = false;
				return success;
			} else {
				log.debug("JOB TAKEN BY = "+cJob.getStatusProperty("trancoder"));
			}
		} else {
			log.debug("No job found");
		}
		busy = false;	
		return false;
	}
	
	public void remoteSignal(String from,String method,String url) {
		if (from.equals("localhost") || method.equals("POST")) {
			if (!busy) {
				boolean donework = checkForNewJob();
				while (donework) {
					donework = checkForNewJob();
				}
			}
		}
	}
	
	/**
	 * Get the current executing job
	 * 
	 * @return
	 */
	public Job getCurrentJob() {
		return cJob;
	}
	
	/**
	 * Handles the job
	 * 
	 * @param job
	 */
	public boolean transcode() {
		// get uri and streams
		TFactory tf = new TFactory();
		
		// set the reencode to false
		tf.setReencodeToFalse(cJob);
		
		// transcode
		return tf.transcode(cJob);
	}
	
	/**
	 * Removes job from queue 
	 * 
	 * @param job
	 */
	public void removeJob() {
		log.debug("removing job: "+cJob);
		
		// send delete call
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return;
		smithers.delete( cJob.getUri(), null, null);
		log.debug("send delete call to "+cJob.getUri());
	}
	
	/**
	 * set the properties in the rawaudio after transcoding
	 * 
	 * @param job
	 * @param success
	 */
	private void jobFinished(boolean success){
		log.debug("call to jobFinished");
		
		// rawaudio uri 
		String rawUri = cJob.getProperty("referid");
		
		// set the transferred property
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return;
		String response = smithers.put(rawUri + "/properties/transferred", "false", "text/xml");
		
		if (success){				
			// set the status property to done
			smithers.put(rawUri + "/properties/status", "done", "text/xml");
		}else{		
			// set the status property to fail
			smithers.put(rawUri + "/properties/status", "failed", "text/xml");
		}
		//Check if an additional script is provided to run after the job finished
		String mount = cJob.getProperty("mount");
		if (mount.indexOf(",") > -1) {
			mount = mount.substring(0,mount.indexOf(","));
		}
		
		MountProperties mp = LazyHomer.getMountProperties(mount);
		String jobFinished = mp.getJobFinished();
		if (jobFinished != null && !jobFinished.equals("")) {
			log.debug("About to run script "+jobFinished);
			String batchFilesPath = WillieServer.instance().getConfiguration().getProperty("batchFilesPath");
			String batchFilesExtension = WillieServer.instance().getConfiguration().getProperty("batchFilesExtension");
			
			String filename = cJob.getProperty("filename");
			String filePath = filename.substring(0, filename.lastIndexOf("/"));
			
			String[] cmdArray = new String[] {batchFilesPath+File.separator+jobFinished+batchFilesExtension, filePath};
			log.debug("About to run "+batchFilesPath+File.separator+jobFinished+batchFilesExtension+" "+filePath);
			CommandRunner.run(cmdArray);
		}
	}
}