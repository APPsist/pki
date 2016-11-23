package de.appsist.service.pki.util;

import java.util.List;
import java.util.Map;

/**
 * Enumeration to classify map entries for a JSON object. 
 * @author simon.schwantzer(at)im-c.de
 */
public enum EntryType {
	STRING, BOOLEAN, NUMBER, ARRAY, OBJECT, OTHER;
	
	public static EntryType getType(Object object) {
		if (object instanceof String) {
			return STRING;
		} else if (object instanceof Boolean) {
			return BOOLEAN;
		} else if (object instanceof Number) {
			return NUMBER;
		} else if (object instanceof List<?>) {
			return ARRAY;
		} else if (object instanceof Map<?,?>) {
			return OBJECT;
		} else {
			return OTHER;
		}
	}
}