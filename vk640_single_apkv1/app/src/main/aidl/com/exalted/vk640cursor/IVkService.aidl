package com.exalted.vk640cursor;

interface IVkService {
    /** Shizuku icin zorunlu: kullanici servisini kapatir. */
    void destroy() = 16777114;
    /** Surucuyu hazirlar ve baslatir. srcBin: APK icindeki libvk640_uhid.so yolu. rot: auto|0|90|180|270 */
    String start(String srcBin, String rot);
    /** Calisan surec durumu ve surucu logunun sonu. */
    String status();
    /** Dongu betigini ve surucuyu kapatir. */
    String stop();
}
