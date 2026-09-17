package com.petgyebu.telo.codef;

import static org.assertj.core.api.Assertions.assertThat;

import io.codef.api.EasyCodefUtil;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import javax.crypto.Cipher;
import org.junit.jupiter.api.Test;

/**
 * `05-infra-stack.md` 6장 Action Item: easycodef-java 1.0.6이 JDK 25에서 동작하는지 확인한다.
 * 네트워크 호출 없이 검증 가능한 범위(클래스 로딩 + encryptRSA 암호화)만 다룬다.
 */
class EasyCodefUtilJdk25Test {

	@Test
	void encryptRsaRoundTripsOnJdk25() throws Exception {
		KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
		generator.initialize(2048);
		KeyPair keyPair = generator.generateKeyPair();
		String publicKey = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());

		String plainText = "telo-sprint0-probe";
		String encrypted = EasyCodefUtil.encryptRSA(plainText, publicKey);

		assertThat(encrypted).isNotBlank();

		Cipher cipher = Cipher.getInstance("RSA");
		cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
		String decrypted = new String(cipher.doFinal(Base64.getDecoder().decode(encrypted)),
				StandardCharsets.UTF_8);

		assertThat(decrypted).isEqualTo(plainText);
	}
}
