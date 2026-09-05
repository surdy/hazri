"""MQTT transport seam.

Each simulated node owns its own connection, exactly as a real board does, so
that the last-will ``espresense/rooms/<room>/status = offline`` is per node.
Tests use :class:`FakeTransport` and never touch a broker.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from typing import Callable, Protocol

MessageHandler = Callable[[str, str], None]
ConnectHandler = Callable[[], None]


@dataclass(frozen=True)
class Message:
    topic: str
    payload: str
    qos: int = 0
    retain: bool = False


class Transport(Protocol):
    def set_will(self, topic: str, payload: str, qos: int, retain: bool) -> None: ...

    def subscribe(self, topic_filter: str, qos: int = 0) -> None: ...

    def on_message(self, handler: MessageHandler) -> None: ...

    def on_connect(self, handler: ConnectHandler) -> None: ...

    def publish(
        self, topic: str, payload: str, qos: int = 0, retain: bool = False
    ) -> bool: ...


@dataclass
class FakeTransport:
    """Records publishes; ``deliver`` feeds a message back as if from the broker."""

    published: list[Message] = field(default_factory=list)
    subscriptions: list[str] = field(default_factory=list)
    will: Message | None = None
    connected: bool = False
    _handler: MessageHandler | None = None
    _connect_handler: ConnectHandler | None = None

    def set_will(self, topic: str, payload: str, qos: int, retain: bool) -> None:
        self.will = Message(topic, payload, qos, retain)

    def subscribe(self, topic_filter: str, qos: int = 0) -> None:
        self.subscriptions.append(topic_filter)

    def on_message(self, handler: MessageHandler) -> None:
        self._handler = handler

    def on_connect(self, handler: ConnectHandler) -> None:
        self._connect_handler = handler

    def publish(
        self, topic: str, payload: str, qos: int = 0, retain: bool = False
    ) -> bool:
        self.published.append(Message(topic, payload, qos, retain))
        return True

    # -- test helpers -----------------------------------------------------
    def deliver(self, topic: str, payload: str) -> None:
        if self._handler is None:
            raise RuntimeError("no handler registered")
        self._handler(topic, payload)

    def reconnect(self) -> None:
        """Fire the connect callback, as paho does on a session coming back."""
        self.connected = True
        if self._connect_handler is not None:
            self._connect_handler()

    def topics(self) -> list[str]:
        return [m.topic for m in self.published]

    def last(self, topic: str) -> Message | None:
        for message in reversed(self.published):
            if message.topic == topic:
                return message
        return None

    def clear(self) -> None:
        self.published.clear()


@dataclass
class BrokerConfig:
    host: str = "localhost"
    port: int = 1883
    username: str | None = None
    password: str | None = None
    keepalive: int = 60


class PahoTransport:
    """One paho-mqtt v2 client, connected in its own network thread."""

    def __init__(self, client_id: str, config: BrokerConfig) -> None:
        from paho.mqtt import client as mqtt

        self._client = mqtt.Client(
            mqtt.CallbackAPIVersion.VERSION2, client_id=client_id, clean_session=True
        )
        if config.username:
            self._client.username_pw_set(config.username, config.password or "")
        self._config = config
        self._handler: MessageHandler | None = None
        self._connect_handler: ConnectHandler | None = None
        self._pending_subscriptions: list[tuple[str, int]] = []
        self._last_publish = None
        self._client.on_connect = self._on_connect
        self._client.on_message = self._on_message

    def _on_connect(self, client, userdata, flags, reason_code, properties=None) -> None:
        for topic_filter, qos in self._pending_subscriptions:
            client.subscribe(topic_filter, qos)
        # A reconnect means the broker has already published the will, so the
        # node must announce itself again.
        if self._connect_handler is not None:
            self._connect_handler()

    def _on_message(self, client, userdata, message) -> None:
        if self._handler is not None:
            self._handler(message.topic, message.payload.decode("utf-8", "replace"))

    def set_will(self, topic: str, payload: str, qos: int, retain: bool) -> None:
        self._client.will_set(topic, payload, qos, retain)

    def subscribe(self, topic_filter: str, qos: int = 0) -> None:
        self._pending_subscriptions.append((topic_filter, qos))
        if self._client.is_connected():
            self._client.subscribe(topic_filter, qos)

    def on_message(self, handler: MessageHandler) -> None:
        self._handler = handler

    def on_connect(self, handler: ConnectHandler) -> None:
        self._connect_handler = handler

    def publish(
        self, topic: str, payload: str, qos: int = 0, retain: bool = False
    ) -> bool:
        info = self._client.publish(topic, payload, qos, retain)
        self._last_publish = info
        return info.rc == 0

    def connect(self) -> None:
        self._client.connect(self._config.host, self._config.port, self._config.keepalive)
        self._client.loop_start()

    def disconnect(self, drain_timeout: float = 2.0) -> None:
        """Let the final publish (the retained `offline`) reach the wire before
        the network loop stops, otherwise it is dropped on the floor."""
        if self._last_publish is not None:
            try:
                self._last_publish.wait_for_publish(timeout=drain_timeout)
            except (ValueError, RuntimeError):
                pass  # never queued, or the client is already gone
        self._client.disconnect()
        self._client.loop_stop()
