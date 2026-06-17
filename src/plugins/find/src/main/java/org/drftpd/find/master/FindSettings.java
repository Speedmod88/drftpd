package org.drftpd.find.master;

import org.drftpd.master.GlobalContext;
import org.drftpd.master.commands.CommandRequest;
import org.drftpd.master.vfs.DirectoryHandle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class FindSettings {

    private boolean _quiet;

    private int _limit;
    private int _maxLimit;

    private DirectoryHandle _dirHandle;

    private boolean _dupe2Enabled;

    private String _dupe2RequiredText;

    private List<String> _dupe2ReplacementTexts;

    public FindSettings(CommandRequest request) {

        _limit = Integer.parseInt(request.getProperties().getProperty("limit.default", "5"));
        _maxLimit = Integer.parseInt(request.getProperties().getProperty("limit.max", "20"));

        _quiet = false;
        _dupe2Enabled = false;
        _dupe2RequiredText = null;
        _dupe2ReplacementTexts = Collections.emptyList();

        // We by default initialize to root!
        _dirHandle = GlobalContext.getGlobalContext().getRoot();
    }

    public boolean getQuiet() {
        return _quiet;
    }

    public void setQuiet(boolean quiet) {
        _quiet = quiet;
    }

    public int getLimit() {
        return _limit;
    }

    public void setLimit(int limit) {
        _limit = limit;
    }

    public int getMaxLimit() {
        return _maxLimit;
    }

    public void setMaxLimit(int maxLimit) {
        _maxLimit = maxLimit;
    }

    public DirectoryHandle getDirectoryHandle() {
        return _dirHandle;
    }

    public void setDirectoryHandle(DirectoryHandle dirHandle) {
        _dirHandle = dirHandle;
    }

    public boolean getDupe2Enabled() {
        return _dupe2Enabled;
    }

    public void setDupe2Enabled(boolean dupe2Enabled) {
        _dupe2Enabled = dupe2Enabled;
    }

    public String getDupe2RequiredText() {
        return _dupe2RequiredText;
    }

    public void setDupe2RequiredText(String dupe2RequiredText) {
        _dupe2RequiredText = dupe2RequiredText;
    }

    public List<String> getDupe2ReplacementTexts() {
        return Collections.unmodifiableList(_dupe2ReplacementTexts);
    }

    public void setDupe2ReplacementTexts(List<String> dupe2ReplacementTexts) {
        _dupe2ReplacementTexts = new ArrayList<>(dupe2ReplacementTexts);
    }
}
