var PKI = (function($) {
	var rootProcessId;
	var templates = {};
	
	var eb;
	
	var cache = [];
	
	var init = function() {
		console.log('Initializing templates ...');
		templates.instance = Handlebars.compile($('#process-instance-template').html());
		templates.task = Handlebars.compile($('#process-step-task-template').html());
		templates.request = Handlebars.compile($('#process-step-request-template').html());
		templates.activity = Handlebars.compile($('#process-step-activity-template').html());
		templates.start = Handlebars.compile($('#process-start-template').html());
		templates.end = Handlebars.compile($('#process-end-template').html());
		templates.error = Handlebars.compile($('#process-error-template').html());
		templates.cancelled = Handlebars.compile($('#process-cancelled-template').html());
		templates.terminated = Handlebars.compile($('#process-terminated-template').html());
		
		console.log('Try to fetch process data ...');
		$.ajaxSetup({beforeSend: function(xhr) {
			if (xhr.overrideMimeType) {
				xhr.overrideMimeType("application/json");
			}
		}});
		
		eb = new vertx.EventBus('/eventbus');
		eb.onclose = function() {
			console.log("WARNING: EventBus connection closed!");
		};
		eb.onopen = function() {
			console.log("INFO: EventBus connection opened!");
			registerHandlers();
			$.getJSON('../processes', function(data) {
				console.log('Received process list:', data);
				var processSelect = $('#processSelect');
				$.each(data.processDefinitions, function(index, processId) {
					var option = $('<option></option>');
					option.attr('value', processId);
					$.getJSON('../processes/' + processId, function(data) {
						option.text(data.label + " (" + data.id + ")");
						processSelect.append(option);
					});
				});
				selectPage('main');
			});
		};
	};
	
	function appendToHistory(instanceId, html) {
		var history = $('#history-' + instanceId);
		if (history.length == 0) {
			cache.push(html);
		} else {
			history.append(html);
		}
	}
	
	function registerHandlers() {
		eb.registerHandler("appsist:event:processEvent:processStart", function(event) {
			console.log('[EVENT] processStart', event)
			var data = {};
			data.process = {};
			data.process.id = event.payload.processId;
			data.instance = {};
			data.instance.id = event.payload.processInstanceId;
			data.rootProcessId = event.payload.rootProcessId;
			var html = templates.instance(data);
			$('#processLogs').append(html);

			html = templates.start({instanceId : data.instance.id});
			var history = $('#history-' + data.instance.id);
			history.append(html);
			$.each(cache, function(index, entry) {
				history.append(entry);
			});
			cache = [];
			
			selectInstance(data.instance.id);
			

			$.getJSON('../processes/' + data.process.id, function(response) {
				var processName = response.label;
				
				var tab = $('<button id="tab-' + data.instance.id + '"></button>');
				tab.text(processName + ' (' + data.process.id + ')');
				tab.on('click', {
					instanceId : data.instance.id
				}, function(event) {
					selectInstance(event.data.instanceId);
				});
				$('#processTabs').append(tab);
			});
			
		});
				
		eb.registerHandler("appsist:event:processEvent:processComplete", function(event) {
			console.log('[EVENT] processComplete', event);
			var data = event.payload;
			var info = {};
			info.instanceId = data.processInstanceId;
			var html = templates.end(info);
			appendToHistory(info.instanceId, html);
		});
		
		eb.registerHandler("appsist:event:processEvent:processError", function(event) {
			console.log('[EVENT] processError', event);
			var data = event.payload;
			var info = {};
			info.instanceId = data.processInstanceId;
			info.error = data.error;
			var html = templates.error(info);
			appendToHistory(info.instanceId, html);
		});
		
		eb.registerHandler("appsist:event:processEvent:processCancelled", function(event) {
			console.log('[EVENT] processCancelled', event);
			var data = event.payload;
			var info = {};
			info.instanceId = data.processInstanceId;
			var html = templates.cancelled(info);
			appendToHistory(info.instanceId, html);
		});
		
		eb.registerHandler("appsist:event:processEvent:processTerminated", function(event) {
			console.log('[EVENT] processTerminated', event);
			var data = event.payload;
			var info = {};
			info.instanceId = data.processInstanceId;
			var html = templates.terminated(info);
			appendToHistory(info.instanceId, html);
		});
		
		eb.registerHandler("appsist:event:processEvent:userTask", function(event) {
			console.log('[EVENT] userTask', event);
			var data = {};
			data.instanceId = event.payload.processInstanceId;
			data.elementId = event.payload.elementId;
			data.title = event.payload.task.title;
			data.next = true;
			var html = templates.task(data);
			appendToHistory(data.instanceId, html);
		});
		
		eb.registerHandler("appsist:event:processEvent:serviceTask", function(event) {
			console.log('[EVENT] serviceTask', event);
			var data = {};
			data.instanceId = event.payload.processInstanceId;
			data.elementId = event.payload.elementId;
			data.title = event.payload.task.title;
			var html = templates.task(data);
			appendToHistory(data.instanceId, html);
		});
		
		eb.registerHandler("appsist:event:processEvent:manualTask", function(event) {
			console.log('[EVENT] manualTask', event);
			var data = {};
			data.instanceId = event.payload.processInstanceId;
			data.elementId = event.payload.elementId;
			data.title = event.payload.task.title;
			data.next = true;
			var html = templates.task(data);
			appendToHistory(data.instanceId, html);
		});
		
		eb.registerHandler("appsist:event:processEvent:callActivity", function(event) {
			console.log('[EVENT] callActivity', event);
			var data = {};
			data.activity = event.payload.activity;
			data.instanceId = event.payload.processInstanceId;
			data.elementId = event.payload.elementId;
			var html = templates.activity(data);
			appendToHistory(data.instanceId, html);
		});
		
		eb.registerHandler("appsist:event:processEvent:userRequest", function(event) {
			console.log('[EVENT] userRequest', event);
			var data = {};
			data.message = event.payload.request.message;
			data.options = event.payload.request.options;
			data.instanceId = event.payload.processInstanceId;
			var html = templates.request(data);
			appendToHistory(data.instanceId, html);
		});
		
	}
	
	var selectProcess = function() {
		rootProcessId = $('#processSelect option:selected').val();
		
		selectPage('process');
		$.post('../processes/' + rootProcessId + '/instantiate', function(data) {
			console.log('Received instantiation response:', data);
		}, 'json');
	};
	
	var cancel = function(instanceId) {
		$.post('../instances/' + instanceId + '/cancel');
	};
	
	var close = function(instanceId) {
		$('#tab-' + instanceId).remove();
		$('#instance-' + instanceId).remove();
		if ($('#processTabs').html().trim().length == 0) {
			selectPage('main');
		}
	};
	
	var next = function(instanceId, elementId) {
		var path = '../instances/' + instanceId + '/next';
		if (elementId) {
			path += '?elementId=' + elementId;
		}
		
		$.post(path, function(data) {
			// nothing
		}, 'json');
	};
	
	var confirm = function(instanceId) {
		$.post('../instances/' + instanceId + '/confirm');
	};
	
	function viewError(error) {
		var div = $('#error > .body'); 
		div.html(error);
		selectPage('error');
	}
	
	function selectPage(page) {
		$('.page').hide();
		$('#' + page).show();
	}
	
	function selectInstance(instanceId) {
		$('.instance').hide();
		$('#instance-' + instanceId).show();
	}
	
	function getURLParameter(name) {
		return decodeURIComponent((new RegExp('[?|&]' + name + '=' + '([^&;]+?)(&|#|;|$)').exec(location.search)||[,""])[1].replace(/\+/g, '%20'))||null;
	}
	
	return {
		init : init,
		selectProcess : selectProcess,
		next : next,
		confirm : confirm,
		cancel : cancel,
		close : close
	}
})($);