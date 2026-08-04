/**
 * Restricted system-chat SSE lifecycle only. Server-rendered history and
 * proposal controls remain HTMX fragments; all stream text is inert DOM text.
 */
;(function () {
  'use strict';

  var MAX_MESSAGE_LENGTH = 16000;
  var MAX_EVENT_PAYLOAD = 128000;
  var PANEL_ID = 'system-chat-panel';
  var HISTORY_ID = 'system-chat-history';
  var PROPOSALS_ID = 'system-chat-proposals';
  var activeController = null;
  var activePanel = null;

  function panel() { return document.getElementById(PANEL_ID); }
  function history() { return document.getElementById(HISTORY_ID); }
  function input() { return document.getElementById('system-chat-input'); }
  function status() {
    var host = panel();
    return host ? host.querySelector('.system-chat-stream-status') : null;
  }

  function setStatus(text) {
    var host = status();
    if (host) host.textContent = text || '';
  }

  function setLoading(loading) {
    var host = panel();
    if (!host) return;
    var send = host.querySelector('#system-chat-send-btn');
    var cancel = host.querySelector('#system-chat-cancel-btn');
    var message = input();
    if (send) send.hidden = loading;
    if (cancel) cancel.hidden = !loading;
    if (message) message.disabled = loading;
  }

  function resetStream(clearStatus) {
    if (activeController) activeController.abort();
    activeController = null;
    activePanel = null;
    setLoading(false);
    if (clearStatus) setStatus('');
  }

  function appendMessage(role, text) {
    var host = history();
    if (!host) return;
    var message = document.createElement('div');
    message.className = 'system-chat-msg ' + (role === 'user' ? 'system-chat-msg-user' : 'system-chat-msg-assistant');
    var label = document.createElement('div');
    label.className = 'system-chat-msg-role';
    label.textContent = role === 'user' ? 'You' : 'Assistant';
    var body = document.createElement('div');
    body.className = 'system-chat-msg-text';
    body.textContent = text || '';
    message.append(label, body);
    host.appendChild(message);
    host.scrollTop = host.scrollHeight;
  }

  function refreshProposals() {
    if (window.htmx && document.getElementById(PROPOSALS_ID)) {
      window.htmx.trigger(document.body, 'refreshProposals');
    }
  }

  function validStart(data, expectedAgent, expectedConversation) {
    return data && data.agentId === expectedAgent && typeof data.conversationId === 'string'
      && (!expectedConversation || data.conversationId === expectedConversation);
  }

  function handleEvent(name, data, expectedAgent, expectedConversation) {
    if (name === 'start') {
      if (!validStart(data, expectedAgent, expectedConversation)) {
        setStatus('Stream identity did not match this agent.');
        resetStream(false);
        return false;
      }
      setStatus('Assistant is thinking…');
      return true;
    }
    if (name === 'message') {
      if (!data || data.role !== 'assistant' || typeof data.text !== 'string' || data.text.length > MAX_EVENT_PAYLOAD) return false;
      appendMessage('assistant', data.text);
      setStatus('');
      return true;
    }
    if (name === 'proposals') return Array.isArray(data && data.proposals);
    if (name === 'done') {
      if (!data || data.conversationId !== expectedConversation) return false;
      resetStream(true);
      refreshProposals();
      return false;
    }
    if (name === 'error') {
      setStatus('System chat is unavailable.');
      resetStream(false);
      return false;
    }
    return false;
  }

  function parseEvent(block) {
    if (block.length > MAX_EVENT_PAYLOAD) return null;
    var eventName = '';
    var dataLines = [];
    block.replace(/\r/g, '').split('\n').forEach(function (line) {
      if (line.indexOf('event:') === 0) eventName = line.slice(6).trim();
      if (line.indexOf('data:') === 0) dataLines.push(line.slice(5).trimStart());
    });
    if (!/^(start|message|proposals|error|done)$/.test(eventName)) return null;
    var data = dataLines.join('\n');
    if (data.length > MAX_EVENT_PAYLOAD) return null;
    try { return { name: eventName, data: JSON.parse(data) }; } catch (_) { return null; }
  }

  async function stream(agentId, conversationId, message) {
    activeController = new AbortController();
    activePanel = panel();
    var controller = activeController;
    try {
      var response = await fetch('/api/agents/' + encodeURIComponent(agentId) + '/system-chat/stream', {
        method: 'POST',
        headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ message: message }),
        signal: controller.signal
      });
      if (!response.ok || !response.body) {
        setStatus('System chat is unavailable.');
        resetStream(false);
        return;
      }
      var reader = response.body.getReader();
      var decoder = new TextDecoder();
      var buffer = '';
      while (!controller.signal.aborted) {
        var read = await reader.read();
        if (read.done) break;
        buffer += decoder.decode(read.value, { stream: true });
        if (buffer.length > MAX_EVENT_PAYLOAD) {
          setStatus('Stream payload exceeded the safety limit.');
          resetStream(false);
          return;
        }
        var boundary;
        while ((boundary = buffer.indexOf('\n\n')) !== -1) {
          var parsed = parseEvent(buffer.slice(0, boundary));
          buffer = buffer.slice(boundary + 2);
          if (!parsed || !handleEvent(parsed.name, parsed.data, agentId, conversationId)) return;
        }
      }
      if (!controller.signal.aborted) resetStream(true);
    } catch (error) {
      if (error.name !== 'AbortError') setStatus('System chat stream failed.');
      resetStream(error.name === 'AbortError');
    }
  }

  function sendMessage(host) {
    var messageInput = input();
    if (!messageInput || activeController) return;
    var message = messageInput.value.trim();
    var agentId = host.getAttribute('data-system-chat-agent');
    var conversationId = host.getAttribute('data-system-chat-conversation');
    if (!agentId || !conversationId || !message || message.length > MAX_MESSAGE_LENGTH) return;
    appendMessage('user', message);
    messageInput.value = '';
    setLoading(true);
    stream(agentId, conversationId, message);
  }

  function init() {
    var host = panel();
    if (!host || host.dataset.systemChatInitialized === 'true') return;
    host.dataset.systemChatInitialized = 'true';
    var send = host.querySelector('#system-chat-send-btn');
    var cancel = host.querySelector('#system-chat-cancel-btn');
    var message = input();
    if (send) send.addEventListener('click', function () { sendMessage(host); });
    if (cancel) cancel.addEventListener('click', function () { resetStream(true); });
    if (message) message.addEventListener('keydown', function (event) {
      if (event.key === 'Enter' && !event.shiftKey) {
        event.preventDefault();
        sendMessage(host);
      }
    });
  }

  document.addEventListener('DOMContentLoaded', init);
  document.body.addEventListener('htmx:beforeSwap', function (event) {
    var target = event.detail && event.detail.target;
    if (activePanel && target && (target === activePanel || target.contains(activePanel))) resetStream(true);
  });
  document.body.addEventListener('htmx:afterSettle', init);
})();
