/**
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 */

package com.ubergeek42.weechat.relay.connection;

import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.UIKeyboardInteractive;
import com.jcraft.jsch.UserInfo;
import com.jcraft.jsch.ChannelExec;
import com.ubergeek42.weechat.relay.JschLogger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.channels.ClosedByInterruptException;
import java.util.regex.Pattern;

public class SSHUnixConnection extends AbstractConnection {

    protected static Logger logger = LoggerFactory.getLogger("SSHUnixConnection");

    private Session sshSession;
    private ChannelExec channel;
    private Socket sock;

    private String sshPassword;
    private String path;

    public SSHUnixConnection(String path, String sshHost, int sshPort, String sshUsername,
                         String sshPassword, byte[] sshKey, byte[] sshKnownHosts) throws JSchException {
        this.path = path;
        this.sshPassword = sshPassword;

        JSch.setLogger(new JschLogger());
        JSch jsch = new JSch();
        jsch.setKnownHosts(new ByteArrayInputStream(sshKnownHosts));
        jsch.setConfig("PreferredAuthentications", "password,publickey");
        boolean useKeyFile = sshKey != null && sshKey.length > 0;
        if (useKeyFile) jsch.addIdentity("key", sshKey, null, sshPassword.getBytes());
        sshSession = jsch.getSession(sshUsername, sshHost, sshPort);
        sshSession.setSocketFactory(new SocketChannelFactory());
        if (!useKeyFile) sshSession.setUserInfo(new WeechatUserInfo());

        channel = (ChannelExec) sshSession.openChannel("exec");
        // TODO: error checking on the path
        channel.setCommand("socat - UNIX-CONNECT:" + this.path);
    }

    @Override protected void doConnect() throws Exception {
        try {
            sshSession.connect();
            channel.connect();
            out = channel.getOutputStream();
            in = channel.getInputStream();
        } catch (RuntimeException e) {
            throw e.getCause() instanceof ClosedByInterruptException ?
                    (ClosedByInterruptException) e.getCause() : e;
        }

    }

    @Override public void doDisconnect() {
        super.doDisconnect();
        sshSession.disconnect();
        try {sock.close();} catch (IOException | NullPointerException ignored) {}
    }

    // this class is preferred than sshSession.setPassword()
    // as it provides a better password prompt matching on some systems
    final private static Pattern PASSWORD_PROMPT = Pattern.compile("(?i)password[^:]*:");

    private class WeechatUserInfo implements UserInfo, UIKeyboardInteractive {
        public String getPassphrase() {return null;}
        public String getPassword() {return sshPassword;}
        public boolean promptPassphrase(String message) {return false;}
        public boolean promptPassword(String message) {return true;}
        public boolean promptYesNo(String message) {return false;}
        public void	showMessage(String message) {}
        public String[] promptKeyboardInteractive(String destination, String name, String instruction, String[] prompt, boolean[] echo) {
            return (prompt.length == 1 && !echo[0] && PASSWORD_PROMPT.matcher(prompt[0]).find()) ?
                    new String[]{sshPassword} : null;
        }
    }
}
