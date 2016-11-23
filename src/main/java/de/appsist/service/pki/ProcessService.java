package de.appsist.service.pki;

import java.util.List;
import java.util.Map;

import de.appsist.commons.process.ProcessDefinition;
import de.appsist.commons.process.ProcessInstance;

/**
 * Publicly exposed functionality for the Process Coordination Instance (PKI).
 * This Java functionality will additionally be exposed via a REST API.
 * 
 * @author MSchmidt
 */
public interface ProcessService {
	
	/*********************************************************************
	 ************************** PROCESS **********************************
	 *********************************************************************/
	/**
	 * Extract IDs of all processes registered at the PKI.
	 * 
	 * @return list of IDs of all available processes
	 */
	public List<String> getAllProcessIds();

	/**
	 * Extract all processes (as PoJos) registered at the PKI.
	 * 
	 * @return list ids of all available processes
	 */
	public List<? extends ProcessDefinition> getAllProcesses();

	/**
	 * Return process as PoJo by process ID.
	 * 
	 * @return process with given processId
	 * @throws IllegalArgumentException in case the process ID cannot be resolved
	 */
	public ProcessDefinition getProcessById(String processId)
	throws IllegalArgumentException;

	/**
	 * Imports a process from the PKI.
	 * 
	 * @throws IllegalArgumentException in case the ID provided in the process definition is in use already
	 */
	public void registerProcess(ProcessDefinition processDefinition)
	throws IllegalArgumentException;
	

	/**
	 * Unregisters a process to the PKI.
	 * 
	 * @throws IllegalArgumentException in case the ID provided cannot be resolved
	 */
	public void unregisterProcess(String processId)
	throws IllegalArgumentException;
	
	
	
	/**********************************************************************
	 *********************** PROCESS INSTANCE *****************************
	 **********************************************************************/
	/**
	 * Retrieve the IDs of all running process instances.
	 * 
	 * @return the IDs of all running process instances
	 */
	public List<String> getRunningProcessInstanceIds();	
	
	/**
	 * Get all currently running process instances.
	 * 
	 * @return all currently running process instances.
	 */
	public List<? extends ProcessInstance> getRunningProcessInstances();	

	/**
	 * Retrieve all running process instances associated with a given process.
	 * 
	 * @param processId the ID of the process for which we look up instances
	 * @return all currently running instances for the given process
	 * @throws IllegalArgumentException in case the process ID cannot be resolved
	 */
	public List<? extends ProcessInstance> getRunningInstancesForProcess(String processId)
	throws IllegalArgumentException;

	/**
	 * Retrieve the process instance with a given process instance ID. 
	 * 
	 * @param processInstanceId the ID of the process instance
	 * @return process instance, if exists, null otherwise
	 * @throws IllegalArgumentException in case the process instance ID cannot be resolved
	 */
	public ProcessInstance getProcessInstanceById(String processInstanceId)
	throws IllegalArgumentException;

	/**
	 * Retrieve a new instance for the process with the given ID.
	 * 
	 * @param processId ID of the process to instantiate
	 * @param userId ID of the user instantiating the process. May be null.
	 * @param sessionId ID of the user session defining the context of the process instantiation.
	 * @param initialContext Initial context to set.
	 * @param parent Parent process instance if the process is called as subprocess, otherwise <code>null</code>.
	 * @return the process instance
	 * @throws IllegalArgumentException in case the process ID cannot be resolved
	 */
	public ProcessInstance instantiateProcess(String processId, String userId, String sessionId, Map<String, Object> initialContext, ProcessInstance parent)
	throws IllegalArgumentException;
}