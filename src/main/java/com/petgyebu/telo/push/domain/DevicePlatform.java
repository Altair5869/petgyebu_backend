package com.petgyebu.telo.push.domain;

/**
 * 푸시를 받을 기기의 플랫폼.
 *
 * <p><b>{@code WEB}이 없다.</b> 웹 푸시를 구현하지 않기로 했다(Q9,
 * {@code docs/02-requirements-features.md} R-ENPLNB 결정 5). 값을 늘리려면 DB의
 * {@code ck_push_device_tokens_platform}을 함께 고쳐야 한다.
 */
public enum DevicePlatform {
	ANDROID,
	IOS
}
