package org.openhab.binding.zigbee.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.zsmartsystems.zigbee.transport.ZigBeePort;

public class ZigBeeNetworkPort implements ZigBeePort {

    private Logger log = LoggerFactory.getLogger(ZigBeePort.class);

    private String hostname;
    private int port;

    private Socket tcpPort;

    private OutputStream outputStream;

    private boolean closed = true;
    private boolean reconnecting = false;
    private int backoff = 500;
    Thread reconnector;

    ReconnectListener listener;

    /**
     * Synchronisation object for buffer queue manipulation
     */
    private Object bufferSynchronisationObject = new Object();

    public ZigBeeNetworkPort(String hostname, int port) {
        this.hostname = hostname;
        this.port = port;
    }

    public void setReconnectListener(ReconnectListener listener) {
        this.listener = listener;
    }

    private void reconnect() {
        if (reconnecting) {
            return; // no need to do it again.
        }
        reconnecting = true;

        reconnector = new Thread(new Runnable() {
            @Override
            public void run() {
                synchronized(bufferSynchronisationObject) {
                    do {
                        if(closed) {
                            reconnecting = false;
                            log.warn("port closed, not reconnecting.");
                            break;
                        } else {
                            log.info("attempting to reconnect.");
                            if (open()) {
                                log.warn("connect successful.");
                                reconnecting = false;
                                backoff = 0;
                                listener.reconnected();
                                break;
                            }
                        }
                        try {
                            log.error("connect failed.");
                            Thread.sleep(backoff);
                            if(backoff < 5000) backoff += 500;
                        } catch (InterruptedException e) {
                            log.error("something interrupted my sleep during reconnection.", e);
                        }
                    } while (reconnecting);

                    log.warn("reconnector: my work is done.");
                }
            }
        });

        reconnector.start();
    }

    @Override
    public boolean open() {
        try {
            tcpPort = new Socket(hostname, port);
        } catch (IOException e) {
            log.error("Unable to connect to " + hostname + " port " + port, e);
            return false;
        }

        try {
            outputStream = tcpPort.getOutputStream();
        } catch (IOException e) {
            return false;
        }

        closed = false;
        return true;
    }

    @Override
    public boolean open(int baudRate) {
        return open();
    }

    @Override
    public boolean open(int baudRate, FlowControl flowControl) {
        return open();
    }

    @Override
    public void close() {
        closed = true;
        log.warn("close()");
        try {
            tcpPort.getInputStream().close();
        } catch (IOException e1) {
            // we ignore this if it fails.
        }
        try {
            outputStream.flush();
        } catch (IOException e) {
            // we ignore this if it fails.
        }
        try {
            outputStream.close();
        } catch (IOException e) {
            // we ignore this if it fails.
        }

        try {
            tcpPort.close();
        } catch (IOException e) {
            // we ignore this if it fails.
        }

        tcpPort = null;
        if (this != null) {
            try {
                this.notify();
            } catch (Exception ex) {
                // we ignore this if it fails.
            }
        }
    }

    @Override
    public void write(int value) {
        if (outputStream == null) {
            return;
        }
        try {
            outputStream.write(value);
        } catch (IOException e) {
//            log.error("error while writing. reconnecting.\n");
            reconnect();
        }
    }

    @Override
    public int read() {
        return read(9999999);
    }

    @Override
    public int read(int timeout) {
//log.warn("read(" + timeout + ")");
        try {
            synchronized (bufferSynchronisationObject) {
                if (tcpPort == null) {
                    log.error("reading on an unconnected socket.\n");
                    return -1;
                }
                tcpPort.setSoTimeout(timeout);
                int rv = tcpPort.getInputStream().read();
                if(rv == -1) reconnect();
                return rv;
            }

        } catch (IOException e) {
            log.error("Error while reading. reconnecting", e);
            reconnect();
            return -1;
        }
    }

    @Override
    public void purgeRxBuffer() {
        synchronized (bufferSynchronisationObject) {
        }
    }

}
