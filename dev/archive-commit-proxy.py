"""PostgreSQL wire fault: discard one successful COMMIT acknowledgement."""
import socket
import threading


class CommitProxy:
    def __init__(self, upstream):
        self.upstream = upstream
        self.armed = threading.Event()
        self.dropped = threading.Event()
        self.stopped = threading.Event()
        self.connections = []
        self.threads = []
        self.listener = socket.socket()
        self.listener.bind(("127.0.0.1", 0))
        self.port = self.listener.getsockname()[1]
        self.listener.listen()
        self.listener.settimeout(0.1)
        self.start(self.accept)

    def start(self, function):
        thread = threading.Thread(target=function, daemon=True)
        self.threads.append(thread)
        thread.start()

    def accept(self):
        while not self.stopped.is_set():
            try:
                client, _ = self.listener.accept()
            except socket.timeout:
                continue
            except OSError:
                return
            server = socket.create_connection(self.upstream)
            self.connections.extend([client, server])
            self.start(lambda c=client, s=server: self.relay_client(c, s))
            self.start(lambda c=client, s=server: self.relay_server(s, c))

    def close_pair(self, *sockets):
        for stream in sockets:
            try:
                stream.shutdown(socket.SHUT_RDWR)
            except OSError:
                pass
            stream.close()

    def relay_client(self, source, target):
        try:
            while data := source.recv(65536):
                target.sendall(data)
        except OSError:
            pass
        finally:
            self.close_pair(source, target)

    def relay_server(self, source, target):
        def exact(size):
            result = bytearray()
            while len(result) < size:
                data = source.recv(size - len(result))
                if not data:
                    raise EOFError()
                result.extend(data)
            return bytes(result)
        try:
            while True:
                header = exact(5)
                payload = exact(int.from_bytes(header[1:], "big") - 4)
                if header[0:1] == b"C" and payload == b"COMMIT\0" and self.armed.is_set():
                    self.armed.clear()
                    self.dropped.set()
                    return
                target.sendall(header + payload)
        except (OSError, EOFError):
            pass
        finally:
            self.close_pair(source, target)

    def __enter__(self):
        return self

    def __exit__(self, *args):
        self.stopped.set()
        self.listener.close()
        self.close_pair(*self.connections)
        for thread in self.threads:
            thread.join(timeout=1)
