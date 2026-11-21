package tacticalview.core.retarget;

/** 标准人形骨骼槽位（对应 Mixamo 命名）。 */
public enum Slot {
    HIPS("hips"),
    SPINE("spine"),
    SPINE1("spine1"),
    SPINE2("spine2"),
    NECK("neck"),
    HEAD("head"),
    L_SHOULDER("leftshoulder"),
    L_ARM("leftarm"),
    L_FOREARM("leftforearm"),
    L_HAND("lefthand"),
    R_SHOULDER("rightshoulder"),
    R_ARM("rightarm"),
    R_FOREARM("rightforearm"),
    R_HAND("righthand"),
    L_UPLEG("leftupleg"),
    L_LEG("leftleg"),
    L_FOOT("leftfoot"),
    L_TOE("lefttoebase"),
    R_UPLEG("rightupleg"),
    R_LEG("rightleg"),
    R_FOOT("rightfoot"),
    R_TOE("righttoebase");

    /** 规范化后的 Mixamo 骨骼名。 */
    public final String key;

    Slot(String key) {
        this.key = key;
    }
}
