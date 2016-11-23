package de.appsist.service.pki;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.vertx.java.core.AsyncResult;
import org.vertx.java.core.AsyncResultHandler;

/**
 * 
 * @author simon.schwantzer(at)im-c.de
 *
 * @param <T> Key class for the result handlers.
 */
public class ResultAggregationHandler<T> {
	private final AsyncResultHandler<Void> finalResultHandler;
	private final Set<AsyncResultHandler<Void>> openRequests;
	private final Map<T, AsyncResultHandler<Void>> resultHandlers;
	private boolean isCompleted;
	private boolean hasSucceeded;
	
	public ResultAggregationHandler(Set<T> requesters, AsyncResultHandler<Void> finalResultHandler) {
		this.finalResultHandler = finalResultHandler;
		openRequests = new HashSet<>();
		isCompleted = false;
		hasSucceeded = true;
		resultHandlers = new HashMap<>();
		for (T requester : requesters) {
			AsyncResultHandler<Void> resultHandler = new AsyncResultHandler<Void>() {

				@Override
				public void handle(AsyncResult<Void> result) {
					openRequests.remove(this);
					checkAndComplete(result);
				}
			};
			resultHandlers.put(requester, resultHandler);
			openRequests.add(resultHandler);
		}
		if (requesters.isEmpty()) {
			finalResultHandler.handle(new AsyncResult<Void>() {

				@Override
				public Void result() {
					return null;
				}

				@Override
				public Throwable cause() {
					return null;
				}

				@Override
				public boolean succeeded() {
					return true;
				}

				@Override
				public boolean failed() {
					return false;
				}
			});
			isCompleted = true;
		}
	}
	
	public AsyncResultHandler<Void> getRequestHandler(T requester) {
		return resultHandlers.get(requester);
	}
	
	
	private void checkAndComplete(final AsyncResult<Void> result) {
		if (isCompleted) return; // We already threw an error.
		hasSucceeded = hasSucceeded && result.succeeded();
		if (openRequests.isEmpty()) {
			isCompleted = true;
			finalResultHandler.handle(new AsyncResult<Void>() {
				
				@Override
				public boolean succeeded() {
					return hasSucceeded;
				}
				
				@Override
				public Void result() {
					return null;
				}
				
				@Override
				public boolean failed() {
					return !succeeded();
				}
				
				@Override
				public Throwable cause() {
					return failed() ? new Throwable("One or more requests failed.") : null;
				}
			});
		}
	}
	
	public void abort() {
		if (!isCompleted) {
			isCompleted = true;
			finalResultHandler.handle(new AsyncResult<Void>() {
				
				@Override
				public boolean succeeded() {
					return false;
				}
				
				@Override
				public Void result() {
					return null;
				}
				
				@Override
				public boolean failed() {
					return true;
				}
				
				@Override
				public Throwable cause() {
					return new Throwable("Result aggregation has been aborted.");
				}
			});
		}
		
	}
	
	
}