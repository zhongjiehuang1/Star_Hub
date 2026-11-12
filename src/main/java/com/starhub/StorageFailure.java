package com.starhub;

final class StorageFailure extends RuntimeException {
    final int status;
    final String code;
    StorageFailure(int status, String code, String message) { super(message); this.status=status; this.code=code; }
    static StorageFailure unavailable() { return new StorageFailure(503,"CLUSTER_UNAVAILABLE","服务器故障，请等待恢复"); }
    static StorageFailure pending() { return new StorageFailure(503,"OPERATION_PENDING","服务器故障，请等待恢复"); }
}
