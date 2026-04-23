package com.clmcat.tock.registry.listener;

import com.clmcat.tock.registry.MasterListener;
import com.clmcat.tock.registry.TockMaster;
import lombok.Getter;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class MasterListeners {
    private TockMaster  tockMaster;
    @Getter
    private Set<MasterListener> listeners = ConcurrentHashMap.newKeySet();

    public MasterListeners(TockMaster tockMaster) {
        this.tockMaster = tockMaster;
    }

    /**
     * 添加监听， 如果 首次添加，并且 Master 是主机状态，则默认触发： onBecomeMaster
     * @param listener
     * @return 添加成功，如果已经存在则返回 false
     */
    public boolean addListener(MasterListener listener) {
        if (listeners.add(listener)) {
            /// 首次添加成功，  如果是 Master ，则默认触发一次 onBecomeMaster
            if (tockMaster.isRunning() && tockMaster.isMaster()) {
                listener.onBecomeMaster();
            }
            return true;
        }
        return false;
    }

    public void removeListener(MasterListener listener) {
        listeners.remove(listener);
    }
}
