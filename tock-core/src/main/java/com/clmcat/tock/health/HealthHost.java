package com.clmcat.tock.health;

import lombok.Getter;
import lombok.Setter;

import java.io.Serializable;
import java.util.List;
@Getter
@Setter
public class HealthHost implements Serializable {
    private static final long serialVersionUID = 1L;

    private List<String> hosts;
    private int port;
    private String key; // 主机 Server 唯一 KEY
}
