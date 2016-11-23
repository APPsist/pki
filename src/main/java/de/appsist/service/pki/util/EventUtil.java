package de.appsist.service.pki.util;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.bind.DatatypeConverter;

import de.appsist.commons.event.AppsistEvent;
import de.appsist.commons.process.TriggerAnnotation;
import de.appsist.service.pki.exception.UnsolvedReferenceException;

/**
 * Utility methods for handling APPsist events.
 * @author simon.schwantzer(at)im-c.de
 */
public final class EventUtil {
	private EventUtil() {};
	
	/**
	 * Generates an event based on the profile given in an process annotation and the locally available data.
	 * @param eventAnnotation Event annotation containing the schema for the event. Contains references to local data.
	 * @param combinedStore Data to resolve references.
	 * @return APPsist event.
	 * @throws UnsolvedReferenceException At least one reference is not available in the given data store. 
	 */
	/*
	public static final Event generateEvent(EventAnnotation eventAnnotation, Map<String, Object> combinedStore) throws UnsolvedReferenceException {
		String eventId = UUID.randomUUID().toString();
		String eventModelId = eventAnnotation.getEventId();
		Map<String, Object> payload = resolveReferenceMap(eventAnnotation.getProperties(), combinedStore);
		return new AppsistEvent(eventId, eventModelId, payload);
	}
	*/
	
	/**
	 * Combines multiple maps to a single data store.
	 * @param maps Maps storing data like the localData annotations and process instance contexts.
	 * @return Combined map. Entries are handled in order of arguments.
	 */
	@SafeVarargs
	public static Map<String, Object> combineMaps(Map<String, Object>... maps) {
		Map<String, Object> combinedMap = new LinkedHashMap<String, Object>();
		for (int i = maps.length - 1; i >= 0; i--) {
			Map<String, Object> map = maps[i];
			if (map != null) {
				combinedMap.putAll(map);
			}
		}
		return combinedMap;
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> resolveReferenceMap(Map<String, Object> referenceMap, Map<String, Object> combinedStore) throws UnsolvedReferenceException {
		Map<String, Object> map = new HashMap<>();
		for (String key : referenceMap.keySet()) {
			Object reference = referenceMap.get(key);
			switch (EntryType.getType(reference)) {
			case STRING:
				String referenceKey = (String) reference;
				if (combinedStore.containsKey(referenceKey)) {
					map.put(key, combinedStore.get(reference));
				} else {
					throw new UnsolvedReferenceException("Failed to resolve reference: " + reference);
				}
				break;
			case ARRAY:
				List<Object> payloadList = resolveReferenceList((List<Object>) reference, combinedStore); 
				map.put(key, payloadList);
				break;
			case OBJECT:
				Map<String, Object> payloadMap = resolveReferenceMap((Map<String, Object>) reference, combinedStore);
				map.put(key, payloadMap);
				break;
			default:
				// Ignore other types.
			}
		}
		return map;
	}
	
	@SuppressWarnings("unchecked")
	public static List<Object> resolveReferenceList(List<Object> referenceList, Map<String, Object> combinedStore) throws UnsolvedReferenceException {
		List<Object> list = new ArrayList<>();
		for (Object reference : referenceList) {
			switch (EntryType.getType(reference)) {
			case STRING:
				String referenceKey = (String) reference;
				if (combinedStore.containsKey(referenceKey)) {
					list.add(combinedStore.get(referenceKey));
				} else {
					throw new UnsolvedReferenceException("Failed to resolve reference: " + reference);
				}
				break;
			case ARRAY:
				List<Object> payloadList = resolveReferenceList((List<Object>) reference, combinedStore); 
				list.add(payloadList);
				break;
			case OBJECT:
				Map<String, Object> payloadMap = resolveReferenceMap((Map<String, Object>) reference, combinedStore);
				list.add(payloadMap);
				break;
			default:
				// Ignore other types.
			}
		}
		return list;
	}
	
	/**
	 * Checks if an event matches a trigger.
	 * @param event Event to match against a trigger.
	 * @param trigger Trigger to match event with.
	 * @param data Data to resolve references.
	 * @return <code>true</code> if the event matches the rules defined in the trigger, otherwise <code>false</code>.
	 * @throws UnsolvedReferenceException A reference in the trigger annotation cannot be solved. 
	 */
	public static boolean doesEventMatch(AppsistEvent event, TriggerAnnotation trigger, Map<String, Object> data) throws UnsolvedReferenceException {
		if (!trigger.getEventId().equals(event.getModelId())) {
			return false;
		}
		if (trigger.getReferences().isEmpty()) {
			return true;
		}
		
		Map<String, Object> referenceData = resolveReferenceMap(trigger.getReferences(), data); 
		Map<String, Object> eventPayload = event.getPayload();
		
		return checkMap(eventPayload, referenceData);
	}
	
	@SuppressWarnings("unchecked")
	private static boolean checkMap(Map<String, Object> map, Map<String, Object> referenceMap) {
		for (String referenceKey : referenceMap.keySet()) {
			Object referenceValue = referenceMap.get(referenceKey);
			if (!map.containsKey(referenceKey)) {
				return false;
			}
			Object mapValue = map.get(referenceKey);
			EntryType mapValueType =  EntryType.getType(mapValue);
			if (EntryType.getType(referenceValue) != mapValueType) {
				return false;
			}
			
			switch (mapValueType) {
			case STRING:
			case BOOLEAN:
			case NUMBER:
				if (!mapValue.equals(referenceValue)) {
					return false;
				}
				break;
			case ARRAY:
				if (!checkList((List<Object>) mapValue, (List<Object>) referenceValue)) {
					return false;
				}
				break;
			case OBJECT:
				if (!checkMap((Map<String, Object>) mapValue, (Map<String, Object>) referenceValue)) {
					return false;
				}
				break;
			default:
				// No more types supported. Ignore.
			}
		}
		return true;
	}
	
	@SuppressWarnings("unchecked")
	private static boolean checkList(List<Object> list, List<Object> referenceList) {
		if (list.size() != referenceList.size()) {
			return false;
		}
		for (int i = 0; i < referenceList.size(); i++) {
			Object referenceValue = referenceList.get(i);
			Object mapValue = list.get(i);
			EntryType mapValueType =  EntryType.getType(mapValue);
			if (EntryType.getType(referenceValue) != mapValueType) {
				return false;
			}
			
			switch (mapValueType) {
			case STRING:
			case BOOLEAN:
			case NUMBER:
				if (!mapValue.equals(referenceValue)) {
					return false;
				}
				break;
			case ARRAY:
				if (!checkList((List<Object>) mapValue, (List<Object>) referenceValue)) {
					return false;
				}
				break;
			case OBJECT:
				if (!checkMap((Map<String, Object>) mapValue, (Map<String, Object>) referenceValue)) {
					return false;
				}
				break;
			default:
				// No more types supported. Ignore.
			}
		}
		return true;
	}
	
	/**
	 * Creates a string representation of the a date.
	 * @param date Date to create string representation for for.
	 * @return ISO 8601 formatted date time string.
	 */
	public static String getDateAsISOString(Date date) {
		Calendar cal = Calendar.getInstance();
		cal.setTime(date);
		return DatatypeConverter.printDateTime(cal);
	}
}
