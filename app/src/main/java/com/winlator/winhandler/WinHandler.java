package com.winlator.winhandler;

import android.view.KeyEvent;
import android.view.MotionEvent;

import com.winlator.XServerDisplayActivity;
import com.winlator.core.StringUtils;
import com.winlator.inputcontrols.ControlsProfile;
import com.winlator.inputcontrols.ExternalController;
import com.winlator.inputcontrols.GamepadState;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.concurrent.Executors;

public class WinHandler {
    private static final short SERVER_PORT = 7947;
    private static final short CLIENT_PORT = 7946;
    public static final byte DINPUT_MAPPER_TYPE_STANDARD = 0;
    public static final byte DINPUT_MAPPER_TYPE_XINPUT = 1;
    private DatagramSocket socket;
    private final ByteBuffer sendData = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer receiveData = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN);
    private final DatagramPacket sendPacket = new DatagramPacket(sendData.array(), 64);
    private final DatagramPacket receivePacket = new DatagramPacket(receiveData.array(), 64);
    private final ArrayDeque<Runnable> actions = new ArrayDeque<>();
    private boolean initReceived = false;
    private boolean running = false;
    private OnGetProcessInfoListener onGetProcessInfoListener;
    private ExternalController currentController;
    private final ArrayDeque<byte[]> gamepadStateQueue = new ArrayDeque<>();
    private InetAddress localhost;
    private byte dinputMapperType = DINPUT_MAPPER_TYPE_XINPUT;
    private final XServerDisplayActivity activity;

    public WinHandler(XServerDisplayActivity activity) {
        this.activity = activity;
    }

    private boolean sendPacket(int port) {
        try {
            int size = sendData.position();
            if (size == 0) return false;
            sendPacket.setAddress(localhost);
            sendPacket.setPort(port);
            socket.send(sendPacket);
            return true;
        }
        catch (IOException e) {
            return false;
        }
    }

    public void exec(String command) {
        synchronized (actions) {
            command = command.trim();
            if (command.isEmpty()) return;
            String[] cmdList = command.split(" ", 2);
            final String filename = cmdList[0];
            final String parameters = cmdList.length > 1 ? cmdList[1] : "";

            actions.add(() -> {
                byte[] filenameBytes = filename.getBytes();
                byte[] parametersBytes = parameters.getBytes();

                sendData.rewind();
                sendData.put(RequestCodes.EXEC);
                sendData.putInt(filenameBytes.length + parametersBytes.length + 8);
                sendData.putInt(filenameBytes.length);
                sendData.putInt(parametersBytes.length);
                sendData.put(filenameBytes);
                sendData.put(parametersBytes);
                sendPacket(CLIENT_PORT);
            });
        }
    }

    public void killProcess(final String processName) {
        synchronized (actions) {
            actions.add(() -> {
                sendData.rewind();
                sendData.put(RequestCodes.KILL_PROCESS);
                byte[] bytes = processName.getBytes();
                sendData.putInt(bytes.length);
                sendData.put(bytes);
                sendPacket(CLIENT_PORT);
            });
        }
    }

    public void listProcesses() {
        synchronized (actions) {
            actions.add(() -> {
                sendData.rewind();
                sendData.put(RequestCodes.LIST_PROCESSES);
                sendData.putInt(0);

                if (!sendPacket(CLIENT_PORT) && onGetProcessInfoListener != null) {
                    onGetProcessInfoListener.onGetProcessInfo(0, 0, null);
                }
            });
        }
    }

    public void setProcessAffinity(final int pid, final int affinityMask) {
        synchronized (actions) {
            actions.add(() -> {
                sendData.rewind();
                sendData.put(RequestCodes.SET_PROCESS_AFFINITY);
                sendData.putInt(8);
                sendData.putInt(pid);
                sendData.putInt(affinityMask);
                sendPacket(CLIENT_PORT);
            });
        }
    }

    public void mouseEvent(int flags, int dx, int dy, int wheelDelta) {
        synchronized (actions) {
            if (!initReceived) return;
            actions.add(() -> {
                sendData.rewind();
                sendData.put(RequestCodes.MOUSE_EVENT);
                sendData.putInt(10);
                sendData.putInt(flags);
                sendData.putShort((short)dx);
                sendData.putShort((short)dy);
                sendData.putShort((short)wheelDelta);
                sendPacket(CLIENT_PORT);
            });
        }
    }

    public OnGetProcessInfoListener getOnGetProcessInfoListener() {
        return onGetProcessInfoListener;
    }

    public void setOnGetProcessInfoListener(OnGetProcessInfoListener onGetProcessInfoListener) {
        synchronized (actions) {
            this.onGetProcessInfoListener = onGetProcessInfoListener;
        }
    }

    private void startSendThread() {
        Executors.newSingleThreadExecutor().execute(() -> {
            while (running) {
                synchronized (actions) {
                    while (initReceived && !actions.isEmpty()) actions.poll().run();
                }
            }
        });
    }

    public void stop() {
        running = false;
        if (socket != null) {
            socket.close();
            socket = null;
        }
    }

    private void handleRequest(byte requestCode, final int port) {
        switch (requestCode) {
            case RequestCodes.INIT: {
                initReceived = true;
                break;
            }
            case RequestCodes.GET_PROCESS: {
                if (onGetProcessInfoListener == null) return;
                receiveData.position(receiveData.position() + 4);
                int numProcesses = receiveData.getShort();
                int index = receiveData.getShort();
                int pid = receiveData.getInt();
                long memoryUsage = receiveData.getLong();
                int affinityMask = receiveData.getInt();

                byte[] bytes = new byte[32];
                receiveData.get(bytes);
                String name = StringUtils.fromANSIString(bytes);

                onGetProcessInfoListener.onGetProcessInfo(index, numProcesses, new ProcessInfo(pid, name, memoryUsage, affinityMask));
                break;
            }
            case RequestCodes.GET_GAMEPAD: {
                boolean isXInput = receiveData.get() == 1;
                final ControlsProfile profile = activity.getInputControlsView().getProfile();
                boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();

                if (!useVirtualGamepad && (currentController == null || !currentController.isConnected())) {
                    releaseCurrentController();
                    currentController = ExternalController.getController(0);
                }

                actions.add(() -> {
                    sendData.rewind();
                    sendData.put(RequestCodes.GET_GAMEPAD);

                    if (currentController != null || useVirtualGamepad) {
                        sendData.putInt(!useVirtualGamepad ? currentController.getDeviceId() : profile.id);
                        sendData.put(dinputMapperType);
                        byte[] bytes = (useVirtualGamepad ? profile.getName() : currentController.getName()).getBytes();
                        sendData.putInt(bytes.length);
                        sendData.put(bytes);
                    }
                    else sendData.putInt(0);

                    sendPacket(port);
                });
                break;
            }
            case RequestCodes.GET_GAMEPAD_STATE: {
                int gamepadId = receiveData.getInt();
                final ControlsProfile profile = activity.getInputControlsView().getProfile();
                boolean useVirtualGamepad = profile != null && profile.isVirtualGamepad();

                if (currentController != null && currentController.getDeviceId() != gamepadId) currentController = null;

                actions.add(() -> {
                    sendData.rewind();
                    sendData.put(RequestCodes.GET_GAMEPAD_STATE);
                    sendData.put((byte)(currentController != null || useVirtualGamepad ? 1 : 0));

                    if (currentController != null || useVirtualGamepad) {
                        sendData.putInt(gamepadId);

                        synchronized (gamepadStateQueue) {
                            if (gamepadStateQueue.isEmpty()) {
                                if (useVirtualGamepad) {
                                    profile.getGamepadState().writeTo(sendData);
                                }
                                else currentController.state.writeTo(sendData);
                            }
                            else sendData.put(gamepadStateQueue.poll());
                        }
                    }

                    sendPacket(port);
                });
                break;
            }
            case RequestCodes.RELEASE_GAMEPAD:
                releaseCurrentController();
                break;
        }
    }

    public void start() {
        try {
            localhost = InetAddress.getLocalHost();
        }
        catch (UnknownHostException e) {}

        running = true;
        startSendThread();
        Executors.newSingleThreadExecutor().execute(() -> {
            try {
                socket = new DatagramSocket(SERVER_PORT);

                while (running) {
                    socket.receive(receivePacket);

                    synchronized (actions) {
                        receiveData.rewind();
                        byte requestCode = receiveData.get();
                        handleRequest(requestCode, receivePacket.getPort());
                    }
                }
            }
            catch (IOException e) {}
        });
    }

    public void saveGamepadState(GamepadState state) {
        synchronized (gamepadStateQueue) {
            if (gamepadStateQueue.size() > 20) gamepadStateQueue.removeLast();
            gamepadStateQueue.add(state.toByteArray());
        }
    }

    private void releaseCurrentController() {
        currentController = null;
        synchronized (gamepadStateQueue) {
            gamepadStateQueue.clear();
        }
    }

    public boolean onGenericMotionEvent(MotionEvent event) {
        boolean handled = false;
        if (currentController != null && currentController.getDeviceId() == event.getDeviceId()) {
            handled = currentController.updateStateFromMotionEvent(event);
        }
        return handled;
    }

    public boolean onKeyEvent(KeyEvent event) {
        boolean handled = false;
        if (currentController != null && currentController.getDeviceId() == event.getDeviceId() && event.getRepeatCount() == 0) {
            int action = event.getAction();

            if (action == KeyEvent.ACTION_DOWN) {
                handled = currentController.updateStateFromKeyEvent(event);
            }
            else if (action == KeyEvent.ACTION_UP) {
                handled = currentController.updateStateFromKeyEvent(event);
            }

            if (handled) saveGamepadState(currentController.state);
        }
        return handled;
    }

    public byte getDInputMapperType() {
        return dinputMapperType;
    }

    public void setDInputMapperType(byte dinputMapperType) {
        this.dinputMapperType = dinputMapperType;
    }
}
