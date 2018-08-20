package com.noterik.springfield.willie.queue;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.log4j.Logger;
import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.DocumentHelper;
import org.dom4j.Node;
import org.springfield.fs.FSXMLBuilder;
import org.springfield.mojo.ftp.URIParser;
import org.springfield.mojo.interfaces.ServiceInterface;
import org.springfield.mojo.interfaces.ServiceManager;

/**
 * Container for jobs
 *
 * @author Derk Crezee <d.crezee@noterik.nl>
 * @author Daniel Ockeloen <daniel@noterik.nl>
 * @copyright Copyright: Noterik B.V. 2012
 * @package com.noterik.springfield.willie.queue
 * @access private
 * @version $Id: Job.java,v 1.31 2012-08-02 04:16:38 daniel Exp $
 *
 * TODO: optimize getOriginal and getInput (audio xml is requested twice)
 */
public class Job {
	/** the Job's log4j logger */
	private static final Logger log = Logger.getLogger(Job.class);
	
	/**
	 * uri
	 */
	private String uri;
	
	/**
	 * uri to original raw audio
	 */
	private String original;
	
	/**
	 * filename of original raw audio
	 */
	private String originalFilename;
	
	/**
	 * uri of audio to transcode
	 */
	private String inputURI;
	
	/**
	 * filename of audio to transcode
	 */
	private String inputFilename;
	
	/**
	 * properties
	 */
	private Map<String,String> properties;
	
	/**
	 * original audio properties
	 */
	private Map<String,String> originalProperties;
	
	/**
	 * jobsid
	 */
	private String id;
	
	/**
	 * path of the audio 
	 */
	private String outputURI;
	
	/**
	 * output filename
	 */
	private String outputFilename;
	
	/**
	 * Valid job
	 */
	private boolean validJob = false;
	
	
	/**
	 * Default constructor
	 * 
	 * @param uri the uri of this job
	 * @param properties the properties of this job
	 */
	public Job(String uri, Map<String,String> properties) {
		this.uri = uri;
		this.properties = properties;
	}
	
	/**
	 * Constructor that parses the document for all the properties
	 * @param uri job uri
	 * @param xml xml document containing all the job properties
	 * @throws DocumentException 
	 */
	public Job(String uri, String xml) throws DocumentException {
		this(uri,new HashMap<String,String>());
		
		// create document
		Document doc = DocumentHelper.parseText(xml);
		
		id = doc.getRootElement().attributeValue("id");
		
		// parse properties
		Node pNode = doc.selectSingleNode("//rawaudio/properties");
		if(pNode!=null) {
			List<Node> children = pNode.selectNodes("child::*");
			Node child;
			for(Iterator<Node> iter = children.iterator(); iter.hasNext(); ) {
				child = iter.next();
				properties.put(child.getName(), child.getText());
				if (child.getName().equals("error") && child.getText().equals("Dead link!")) {
					//non valid job - refer invalid
					return;
				}
			}
		} else {
			//non valid job
			return;
		}
		
		// add referid
		String referid = doc.valueOf("//rawaudio/@referid");
		if(referid!=null) {
			properties.put("referid", referid);
		} else {
			//non valid job
			return;
		}
		
		//both rawaudio and refer found
		validJob = true;
		originalProperties = new HashMap<String,String>();
		
		// parse parent XML
		parseParentXML();
	}
	
	/**
	 * Parse the XML of the parent URI to determine original and input audio
	 */
	private void parseParentXML() {
		log.debug("parsing parent XML for job "+uri);
			
		// get some variables
		String referid = getProperty("referid");
		String parentURI = URIParser.getParentUri(referid);
		String useraw = getProperty("useraw");
		
		// get all the raw audios
		log.debug("sending get request to: " + parentURI);
		
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return;
		String response = smithers.get(parentURI, null, null);

		log.debug("response was: " + response);
		
		// parse document
		try {
			Document doc = DocumentHelper.parseText(response);
			
			// determine original
			Node oNode = doc.selectSingleNode("//rawaudio/properties[original='true']");
			if(oNode!=null) {
				List<Node> children = oNode.selectNodes("child::*");
				Node child;
				for(Iterator<Node> iter = children.iterator(); iter.hasNext(); ) {
					child = iter.next();				
					originalProperties.put(child.getName(), child.getText());
				}
				
				String extension = oNode.valueOf("extension");
				String filename = oNode.valueOf("filename");

				if (filename != null && !filename.equals("")) {
					//check if filename was set, use that
					if (filename.indexOf("/") > -1) {							
						original = filename.substring(0, filename.lastIndexOf("/")+1);
						originalFilename = filename.substring(filename.lastIndexOf("/")+1);
					} else {
						original = parentURI + File.separator + "rawaudio" + File.separator + oNode.getParent().valueOf("@id") + File.separator;
						originalFilename = filename;
					}							
				} else {
					// construct original path and filename
					original = parentURI + "/rawaudio/" + oNode.getParent().valueOf("@id");
					originalFilename = "raw." + extension;
				}
				 
				//set output uri if this was set in the requested raw audio
				if (properties.containsKey("filename") && !properties.get("filename").equals("")) {
					String requestedFilename = properties.get("filename");	
					if (requestedFilename.indexOf("/") > -1) {
						outputURI = requestedFilename.substring(0, requestedFilename.lastIndexOf("/")+1);
						outputFilename = requestedFilename.substring(requestedFilename.lastIndexOf("/")+1);
					} else {
						outputURI = referid+File.separator;
						outputFilename = requestedFilename;
 					}
				} 				
			} else {
				oNode = doc.selectSingleNode("//rawaudio/properties[contains(original,'/domain/')]");
				if (oNode != null) {
					//get original refered audio for original path and filename
					String resp = smithers.get(oNode.valueOf("original"), null, null);
					Document oDoc = DocumentHelper.parseText(resp);
					String filename = oDoc.selectSingleNode("//rawaudio/properties/filename") == null ? "" : oDoc.selectSingleNode("//rawaudio/properties/filename").getText();
					original = filename.substring(0, filename.lastIndexOf("/")+1);
					originalFilename = filename.substring(filename.lastIndexOf("/")+1);

					filename = doc.selectSingleNode("//rawaudio/properties/filename") == null ? "raw." + oNode.valueOf("extension") : doc.selectSingleNode("//rawaudio/properties/filename").getText();
					outputURI = filename.substring(0, filename.lastIndexOf("/")+1);
					outputFilename = filename.substring(filename.lastIndexOf("/")+1);
				} else {
					log.debug("could not find original");
				}
			}
			
			// determine input URI and filename
			if(useraw==null) {
				inputURI = original;
				inputFilename = originalFilename;
			} else {
				Node inpNode = doc.selectSingleNode("//rawaudio[@id='"+useraw+"']/properties");
				if(inpNode!=null) {
					String filename = inpNode.valueOf("filename");
					
					if (filename != null && !filename.equals("")) {
						//check if filename was set, use that
						inputURI = filename.substring(0, filename.lastIndexOf("/")+1);
						inputFilename = filename.substring(filename.lastIndexOf("/")+1);
					} else {
						// construct original path and filename
						inputURI = parentURI + "/rawaudio/" + inpNode.getParent().valueOf("@id");
						inputFilename = "raw." + inpNode.valueOf("extension");
					}
				}
			}
		} catch(Exception e) {
			validJob = false;
			log.error("",e);
		}
		
		log.debug("Result of parsing parent - original: "+original+", originalFilename: "+originalFilename+", inputURI: "+inputURI+", inputFilename: "+inputFilename);
		log.debug("Result of parsing parent - original: "+original+", originalFilename: "+originalFilename+", inputURI: "+inputURI+", inputFilename: "+inputFilename);

	}

	/**
	 * Get single property from the properties
	 * 
	 * @param property
	 * @return property, null if property does not exist
	 */
	public String getProperty(String property) {
		return properties.get(property);
	}
	
	/**
	 * Get a single property from the original audio properties
	 * 
	 * @param property
	 * @return property, null if property does not exist
	 */
	public String getOriginalProperty(String property) {
		return originalProperties.get(property);
	}
	
	/**
	 * Add status message to this job
	 * @param message
	 * @param details
	 */
	public void setStatus(String message, String details) {
		// make xml
		String statusXml = FSXMLBuilder.getFSXMLStatusMessage(message, details, "");
		
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return;
		smithers.put(uri + "/status/1/properties",statusXml,"text/xml");
		
		// debug
		log.debug("Settings status for " + uri + "/status/1/properties");
		log.debug(statusXml);
		
	}

	
	/**
	 * Add an error message to this job
	 * 
	 * @param message
	 * @param details
	 */
	public void setError(String message, String details) {
		
		// get the uri of the rawaudio
		String rawUri = this.getProperty("referid");
		
		// make xml
		String errorXml = FSXMLBuilder.getFSXMLErrorMessage("500",message,details, rawUri);
			
		// set error message
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return;
		String response = smithers.put(uri + "/error/1/properties",errorXml,"text/xml");
		
		// debug
		log.debug("Settings status for " + uri + "/error/1/properties");
		log.debug(errorXml);
	}

	/**
	 * @return the uri
	 */
	public String getUri() {
		return uri;
	}

	/**
	 * @return the original
	 */
	private String getOriginal() {
		return original;
	}
	
	/**
	 * @return the job id
	 */
	public String getId() {
		return id;
	}

	/**
	 * @return the originalFilename
	 */
	private String getOriginalFilename() {
		return originalFilename;
	}

	/**
	 * @return the inputURI
	 */
	public String getInputURI() {
		return inputURI;
	}

	/**
	 * @return the inputFilename
	 */
	public String getInputFilename() {
		return inputFilename;
	}
	
	/**
	 * @return the outputURI
	 */
	public String getOutputURI() {
		return outputURI;
	}
	
	/**
	 * @return the outputFilename
	 */
	public String getOutputFilename() {
		return outputFilename;
	}
	
	/**
	 * toString method.
	 */
	@Override
	public String toString() {
		return properties.toString();
	}
	
	/**
	 * Equals function
	 */
	@Override
	public boolean equals(Object o){
		if(o != null && o instanceof Job){
			if(((Job)o).getUri() != null){
				if(((Job)o).getUri().equals(uri)){
					return true;
				}
			}
		}
		return false;
	}
	
	/**
	 * 
	 * @param property
	 * @return the value of the property, null if the property did not exists or an empty string if there was an error
	 */
	public String getStatusProperty(String property) {
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return "";
		String response = smithers.get(uri + "/status/1/properties/"+property,null,null);

		log.debug("Get status property "+property+" response "+response);
		try { 
			Document xml = DocumentHelper.parseText(response);			
			return xml.selectSingleNode(property) == null ? null : xml.selectSingleNode(property).getText();
		} catch (DocumentException e) {
			log.error("could not parse response "+response);
		}
		return "";
	}

	/**
	 * 
	 * @param property
	 * @return the value of the property, null if the property did not exists or an empty string if there was an error
	 */
	public ArrayList<String> getStatusProperties() {
		ArrayList<String> results =new ArrayList<String>();
		
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return null;
		String response = smithers.get(uri + "/status/1/properties",null,null);

		try { 
			Document doc = DocumentHelper.parseText(response);	
			Node pNode = doc.selectSingleNode("//status/properties");
			if(pNode!=null) {
				List<Node> children = pNode.selectNodes("child::*");
				Node child;
				for(Iterator<Node> iter = children.iterator(); iter.hasNext(); ) {
					child = iter.next();
					results.add(child.getName()+"="+child.getText());
				}
			}

		} catch (DocumentException e) {
			log.error("could not parse response "+response);
		}
		return results;
	}

	
	public void setStatusProperty(String property, String value) {
		// set status message
		ServiceInterface smithers = ServiceManager.getService("smithers");
		if (smithers==null) return;
		smithers.put(uri + "/status/1/properties/"+property, value, "text/xml");
		
		// debug
		log.debug("Settings status for " + uri + "/status/1/properties/"+property);
		log.debug(value);
	}
	
	public boolean isValidJob() {
		return validJob;
	}
}
