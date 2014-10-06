package org.jdownloader.extensions.schedulerV2.actions;

import org.jdownloader.extensions.schedulerV2.helpers.ActionParameter;
import org.jdownloader.extensions.schedulerV2.translate.T;

public class EnableReconnectAction implements IScheduleAction {

    @Override
    public String getStorableID() {
        return "ENABLE_RECONNECT";
    }

    @Override
    public String getReadableName() {
        return T._.action_enableReconnect();
    }

    @Override
    public ActionParameter getParameterType() {
        return ActionParameter.NONE;
    }

    @Override
    public void execute(String parameter) {
        org.jdownloader.settings.staticreferences.CFG_RECONNECT.AUTO_RECONNECT_ENABLED.setValue(true);
    }

}