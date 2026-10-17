package org.drftpd.slave.network;

import org.drftpd.common.network.AsyncResponse;
import java.util.List;

/** Sent only after the master confirms remote-log capability. */
public final class AsyncResponseLog extends AsyncResponse {
    private static final long serialVersionUID = 1L;
    private final List<String> files;
    private final String content;
    private final String error;

    public AsyncResponseLog(String index, List<String> files, String content, String error) {
        super(index);
        this.files = new java.util.ArrayList<>(files);
        this.content = content;
        this.error = error;
    }
    public List<String> getFiles() { return files; }
    public String getContent() { return content; }
    public String getError() { return error; }
}
