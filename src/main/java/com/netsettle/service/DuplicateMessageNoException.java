package com.netsettle.service;

/** 같은 전문번호로 내용이 다른 이체가 들어온 경우. 재전송이 아니라 번호 재사용이므로 거부합니다. */
public class DuplicateMessageNoException extends RuntimeException {

    public DuplicateMessageNoException(String message) {
        super(message);
    }
}
