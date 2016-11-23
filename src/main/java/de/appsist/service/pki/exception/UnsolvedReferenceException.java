package de.appsist.service.pki.exception;

/**
 * Exception thrown when a reference cannot be resolved using all available data stores.
 * @author simon.schwantzer(at)im-c.de
 */
public class UnsolvedReferenceException extends Exception {
	private static final long serialVersionUID = 1L;
	
	public UnsolvedReferenceException(String message) {
		super(message);
	}
}
