package de.appsist.service.pki.bpmn;

import java.util.Date;

import de.appsist.commons.event.AppsistEvent;
import de.appsist.commons.process.ProcessElementInstance;
import de.appsist.commons.process.bpmn.BPMNProcessElement;

/**
 * Class for instances of BPMN elements.
 * @author simon.schwantzer(at)im-c.de
 */
public class BPMNProcessElementInstance implements ProcessElementInstance {
	private final BPMNProcessElement processElement;
	private final BPMNProcessInstance processInstance;
	private final BPMNProcessElementInstance predecessor;
	private Date startTime, endTime;
	private AppsistEvent trigger;
	
	/**
	 * Creates a instance for the given element.
	 * @param processInstance Process instance containing this element.
	 * @param processElement Element this instance instantiates.
	 * @param predecessor Step executed before this step. May be <code>null</code>.
	 */
	public BPMNProcessElementInstance(BPMNProcessInstance processInstance, BPMNProcessElement processElement, BPMNProcessElementInstance predecessor) {
		this.processInstance = processInstance;
		this.processElement = processElement;
		this.predecessor = predecessor;
	}
	
	@Override
	public BPMNProcessElementInstance getPredecessor() {
		return predecessor;
	}
	
	/**
	 * Sets the start time for the instance to the current date time.
	 */
	public void start() {
		this.startTime = new Date();
	}
	
	/**
	 * Sets the end time for the instance to the current date time.
	 * @param trigger Trigger for the completion of the element. May be null.
	 */
	public void end(AppsistEvent trigger) {
		this.endTime = new Date();
	}
	
	@Override
	public BPMNProcessInstance getProcessInstance() {
		return processInstance;
	}

	@Override
	public BPMNProcessElement getProcessElement() {
		return processElement;
	}

	@Override
	public Date getStart() {
		return startTime;
	}

	@Override
	public Date getEnd() {
		return endTime;
	}

	@Override
	public AppsistEvent getTrigger() {
		return trigger;
	}

}
