package com.petgyebu.telo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration;

/**
 * UserDetailsServiceAutoConfiguration을 제외한다. 이 자동설정은 UserDetailsService 빈이 없을 때
 * 랜덤 비밀번호를 가진 user 계정을 만들고 그 값을 표준출력에 찍는데, Cloud Run에서는 그대로
 * Cloud Logging에 남는다. 인증 방식은 Q4에서 확정한 무상태 JWT이므로 이 계정 자체가 불필요하다.
 */
@SpringBootApplication(exclude = UserDetailsServiceAutoConfiguration.class)
public class TeloApplication {

	public static void main(String[] args) {
		SpringApplication.run(TeloApplication.class, args);
	}

}
