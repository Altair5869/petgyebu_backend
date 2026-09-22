package com.petgyebu.telo.transaction;

import static java.util.Map.entry;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.petgyebu.telo.account.domain.Account;
import com.petgyebu.telo.account.repository.AccountRepository;
import com.petgyebu.telo.category.domain.Category;
import com.petgyebu.telo.category.repository.CategoryRepository;
import com.petgyebu.telo.common.time.AppZone;
import com.petgyebu.telo.transaction.domain.ClassificationSource;
import com.petgyebu.telo.transaction.domain.LinkStatus;
import com.petgyebu.telo.transaction.domain.RefundStatus;
import com.petgyebu.telo.transaction.domain.Transaction;
import com.petgyebu.telo.transaction.domain.TransferLink;
import com.petgyebu.telo.transaction.domain.TransferStatus;
import com.petgyebu.telo.transaction.domain.TxnType;
import com.petgyebu.telo.transaction.repository.TransactionRepository;
import com.petgyebu.telo.transaction.repository.TransferLinkRepository;
import com.petgyebu.telo.user.domain.AuthProvider;
import com.petgyebu.telo.user.domain.User;
import com.petgyebu.telo.user.repository.UserRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * {@code transactions}·{@code transfer_links} 스키마가 실제 PostgreSQL에서 설계대로 동작하는지
 * 본다({@code CategorySchemaTest}와 같은 방식).
 *
 * <p>앞선 네 테이블에 없던 것이 여기 다 들어 있다. 관례를 그대로 옮기면 놓친다.
 * <ol>
 *   <li>{@code user_id}·{@code account_id} 비정규화 중복 FK</li>
 *   <li>{@code linked_refund_transaction_id} 자기참조 FK({@code NO ACTION})</li>
 *   <li>{@code transfer_links} 양쪽 열의 <b>각각 독립</b> UNIQUE</li>
 *   <li>DESC 인덱스와 부분 인덱스 — {@code indexdef} 문자열이 기존과 다르다</li>
 *   <li>{@code category_id DEFAULT 99} — 실제로 발화하는 DEFAULT다</li>
 * </ol>
 *
 * <p><b>Docker가 필요하며 조건부 스킵을 두지 않는다.</b>
 */
@SpringBootTest
@Testcontainers
@DisplayName("transactions·transfer_links 스키마 제약 검증")
class TransactionSchemaTest {

	@Container
	@ServiceConnection
	static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16-alpine");

	/** 미분류 카테고리 ID. {@code transactions.category_id}의 DEFAULT다. */
	private static final short UNCLASSIFIED_CATEGORY_ID = 99;

	@Autowired
	private UserRepository userRepository;

	@Autowired
	private AccountRepository accountRepository;

	@Autowired
	private CategoryRepository categoryRepository;

	@Autowired
	private TransactionRepository transactionRepository;

	@Autowired
	private TransferLinkRepository transferLinkRepository;

	@Autowired
	private DataSource dataSource;

	@Test
	@DisplayName("같은 계좌에서 같은 대행사 거래 식별자를 두 번 수집할 수 없다")
	void duplicateCodefTransactionIdInSameAccountIsRejected() {
		Account account = givenAccount("txn-dup-1");
		transactionRepository.saveAndFlush(transaction(account, "codef-1", 1_000L));

		assertThatThrownBy(() -> transactionRepository.saveAndFlush(
				transaction(account, "codef-1", 2_000L)))
				.as("UNIQUE (account_id, codef_transaction_id)가 걸려 있지 않다. 재동기화가 "
						+ "같은 거래를 두 번 쌓는다")
				.isInstanceOf(DataIntegrityViolationException.class)
				// 이름까지 본다. NOT NULL이나 CHECK 위반으로 실패해도 초록이 되는 것을 막는다.
				.rootCause()
				.hasMessageContaining("uq_transactions_account_codef_txn_id");
	}

	@Test
	@DisplayName("같은 계좌라도 대행사 거래 식별자가 다르면 얼마든지 쌓인다")
	void differentCodefTransactionIdInSameAccountIsAllowed() {
		Account account = givenAccount("txn-dup-2");
		transactionRepository.saveAndFlush(transaction(account, "codef-a", 1_000L));

		// 유니크가 (account_id)로 좁혀지면 여기서 깨진다. 그 스키마는 계좌당 거래 한 건만 허용한다.
		Transaction second = transactionRepository.saveAndFlush(
				transaction(account, "codef-b", 1_000L));

		assertThat(second.getId()).isNotNull();
	}

	@Test
	@DisplayName("계좌가 다르면 같은 대행사 거래 식별자를 각각 가질 수 있다")
	void sameCodefTransactionIdInAnotherAccountIsAllowed() {
		User user = givenUser("txn-dup-3");
		Account first = givenAccount(user, "110-****-1111");
		Account second = givenAccount(user, "110-****-2222");
		transactionRepository.saveAndFlush(transaction(first, "codef-same", 1_000L));

		// 유니크가 (codef_transaction_id) 단독이면 여기서 깨진다. 식별자는 은행·계좌마다
		// 따로 발급되므로 다른 계좌에서 겹칠 수 있다.
		Transaction other = transactionRepository.saveAndFlush(
				transaction(second, "codef-same", 1_000L));

		assertThat(other.getId()).isNotNull();
	}

	@Test
	@DisplayName("금액은 0 이하일 수 없다 — 방향은 txn_type이 정한다")
	void nonPositiveAmountIsRejected() {
		Account account = givenAccount("txn-amount-1");

		// 지출을 음수로 저장하는 구현이 섞여 들어오는 것을 DB가 막는다.
		assertThatThrownBy(() -> transactionRepository.saveAndFlush(
				transaction(account, "codef-neg", -1_000L)))
				.as("CHECK (amount > 0)이 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_transactions_amount_positive");

		assertThatThrownBy(() -> transactionRepository.saveAndFlush(
				transaction(account, "codef-zero", 0L)))
				.as("0원 거래가 저장됐다. CHECK (amount > 0)이 (amount >= 0)으로 느슨해졌다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_transactions_amount_positive");
	}

	@Test
	@DisplayName("정의되지 않은 열거형 값은 저장할 수 없다 — CHECK 제약 넷")
	void undefinedEnumValuesAreRejected() {
		// 엔티티 쪽은 열거형이라 잘못된 값이 들어갈 수 없다. CHECK가 실제로 붙어 있는지는
		// SQL로 직접 넣어봐야 드러난다.
		Account account = givenAccount("txn-check-1");

		assertRawInsertRejected(account, "codef-ck-1",
				Map.of("txn_type", "TRANSFER"), "ck_transactions_txn_type");
		assertRawInsertRejected(account, "codef-ck-2",
				Map.of("initial_classification_source", "USER_EDITED"),
				"ck_transactions_initial_classification_source");
		assertRawInsertRejected(account, "codef-ck-3",
				Map.of("transfer_status", "LINKED"), "ck_transactions_transfer_status");
		assertRawInsertRejected(account, "codef-ck-4",
				Map.of("refund_status", "CANCELLED"), "ck_transactions_refund_status");
	}

	@Test
	@DisplayName("명세에 있는 열거형 값은 전부 저장된다 — CHECK가 과도하게 좁지 않다")
	void definedEnumValuesAreAccepted() {
		// 거부 케이스만 보면 CHECK에서 값을 하나 빼도 통과한다. 예를 들어 txn_type 목록에서
		// 'INCOME'이 사라지면 수입 거래를 DB가 거부하는데 거부 단언은 전부 초록이다.
		// T-023에서 UNIQUE에 대해 얻은 교훈을 CHECK에 옮긴 것이다.
		Account account = givenAccount("txn-check-ok-1");

		assertRawInsertAccepted(account, "codef-ok-income", Map.of("txn_type", "INCOME"));
		assertRawInsertAccepted(account, "codef-ok-expense", Map.of("txn_type", "EXPENSE"));

		assertRawInsertAccepted(account, "codef-ok-auto",
				Map.of("initial_classification_source", "AUTO_MATCHED"));
		assertRawInsertAccepted(account, "codef-ok-unclassified",
				Map.of("initial_classification_source", "UNCLASSIFIED"));

		// 이체 상태 5종. 부분 인덱스가 걸린 PENDING_CONFIRM을 포함해 전부 들어가야 한다.
		assertRawInsertAccepted(account, "codef-ok-tr-none",
				Map.of("transfer_status", "NONE"));
		assertRawInsertAccepted(account, "codef-ok-tr-pending",
				Map.of("transfer_status", "PENDING_CONFIRM"));
		assertRawInsertAccepted(account, "codef-ok-tr-auto",
				Map.of("transfer_status", "AUTO_LINKED"));
		assertRawInsertAccepted(account, "codef-ok-tr-user",
				Map.of("transfer_status", "USER_CONFIRMED"));
		assertRawInsertAccepted(account, "codef-ok-tr-unlinked",
				Map.of("transfer_status", "UNLINKED"));

		assertRawInsertAccepted(account, "codef-ok-rf-none",
				Map.of("refund_status", "NONE"));
		assertRawInsertAccepted(account, "codef-ok-rf-original",
				Map.of("refund_status", "ORIGINAL"));
		assertRawInsertAccepted(account, "codef-ok-rf-refund",
				Map.of("refund_status", "REFUND"));
	}

	@Test
	@DisplayName("category_id를 주지 않으면 99(미분류)가 들어간다 — DEFAULT가 실제로 발화한다")
	void categoryIdDefaultsToUnclassified() {
		// 다른 테이블의 DEFAULT와 달리 이 DEFAULT에는 실제 사용처가 있다. 자동 분류에서
		// 매칭되는 룰이 없는 거래가 이 경로로 들어온다(T-019).
		Account account = givenAccount("txn-default-1");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		jdbc.update(
				"INSERT INTO transactions (user_id, account_id, codef_transaction_id, "
						+ "transacted_at, amount, txn_type, initial_classification_source) "
						+ "VALUES (?, ?, ?, now(), ?, ?, ?)",
				account.getUser().getId(), account.getId(), "codef-default",
				1_000L, "EXPENSE", "UNCLASSIFIED");

		Map<String, Object> row = jdbc.queryForMap(
				"SELECT CAST(category_id AS integer) AS category_id, transfer_status, refund_status "
						+ "FROM transactions WHERE codef_transaction_id = 'codef-default'");

		assertThat(row.get("category_id"))
				.as("category_id의 DEFAULT가 99(미분류)가 아니다. categories 시드의 UNCLASSIFIED와 "
						+ "맞춰야 한다")
				.isEqualTo(99);
		assertThat(row.get("transfer_status"))
				.as("transfer_status의 DEFAULT가 'NONE'이 아니다")
				.isEqualTo("NONE");
		assertThat(row.get("refund_status"))
				.as("refund_status의 DEFAULT가 'NONE'이 아니다")
				.isEqualTo("NONE");
	}

	@Test
	@DisplayName("원거래와 환불을 두 행으로 저장하고 자기참조로 잇는다")
	void refundLinksToOriginalTransaction() {
		Account account = givenAccount("txn-refund-1");
		Transaction original = transactionRepository.saveAndFlush(
				transaction(account, "codef-origin", 30_000L));
		Transaction refund = transactionRepository.saveAndFlush(
				transaction(account, "codef-refund", 30_000L));

		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.update(
				"UPDATE transactions SET refund_status = 'REFUND', "
						+ "linked_refund_transaction_id = ? WHERE id = ?",
				original.getId(), refund.getId());

		// 순액이 아니라 두 행 그대로 남아 있어야 한다(docs/09-db-design.md 3.4절).
		Long linked = jdbc.queryForObject(
				"SELECT linked_refund_transaction_id FROM transactions WHERE id = ?",
				Long.class, refund.getId());
		assertThat(linked)
				.as("자기참조 FK가 연결을 보존하지 못했다")
				.isEqualTo(original.getId());
		assertThat(count("SELECT count(*) FROM transactions WHERE account_id = ?", account.getId()))
				.as("원거래와 환불이 두 행으로 남아 있지 않다")
				.isEqualTo(2);
	}

	@Test
	@DisplayName("환불이 가리키는 원거래는 지울 수 없다 — 자기참조 FK는 NO ACTION이다")
	void deletingLinkedOriginalTransactionIsRejected() {
		Account account = givenAccount("txn-refund-2");
		Transaction original = transactionRepository.saveAndFlush(
				transaction(account, "codef-origin-2", 30_000L));
		Transaction refund = transactionRepository.saveAndFlush(
				transaction(account, "codef-refund-2", 30_000L));

		JdbcTemplate jdbc = new JdbcTemplate(dataSource);
		jdbc.update("UPDATE transactions SET linked_refund_transaction_id = ? WHERE id = ?",
				original.getId(), refund.getId());

		// ON DELETE 절이 설계 문서에 없어 기본값 NO ACTION이다. CASCADE가 붙으면 원거래 하나를
		// 지웠을 때 환불 행까지 조용히 사라진다.
		assertThatThrownBy(() -> jdbc.update("DELETE FROM transactions WHERE id = ?",
				original.getId()))
				.as("참조 중인 원거래가 삭제됐다. 자기참조 FK에 ON DELETE CASCADE가 붙어 있다")
				.isInstanceOf(DataIntegrityViolationException.class);

		assertThat(count("SELECT count(*) FROM transactions WHERE id = ?", refund.getId()))
				.as("환불 행이 사라졌다")
				.isEqualTo(1);
	}

	@Test
	@DisplayName("계좌를 지우면 거래도 함께 지워진다 — ON DELETE CASCADE")
	void deletingAccountCascadesToTransactions() {
		Account account = givenAccount("txn-cascade-1");
		transactionRepository.saveAndFlush(transaction(account, "codef-cascade-1", 1_000L));

		new JdbcTemplate(dataSource).update("DELETE FROM accounts WHERE id = ?", account.getId());

		assertThat(count("SELECT count(*) FROM transactions WHERE account_id = ?", account.getId()))
				.as("accounts FK에 ON DELETE CASCADE가 없다")
				.isZero();
	}

	@Test
	@DisplayName("사용자를 지우면 거래도 함께 지워진다 — 비정규화한 user_id의 CASCADE")
	void deletingUserCascadesToTransactions() {
		User user = givenUser("txn-cascade-2");
		Account account = givenAccount(user, "110-****-3333");
		transactionRepository.saveAndFlush(transaction(account, "codef-cascade-2", 1_000L));

		new JdbcTemplate(dataSource).update("DELETE FROM users WHERE id = ?", user.getId());

		assertThat(count("SELECT count(*) FROM transactions WHERE user_id = ?", user.getId()))
				.as("users FK에 ON DELETE CASCADE가 없다. 탈퇴(F-ZPNVKT)가 이 동작에 의존한다")
				.isZero();
	}

	@Test
	@DisplayName("하나의 출금은 하나의 입금과만 엮인다 — 출금 열 단독 UNIQUE")
	void oneWithdrawalCannotLinkToTwoDeposits() {
		Account account = givenAccount("link-w-1");
		Transaction withdrawal = transactionRepository.saveAndFlush(
				transaction(account, "codef-w-1", 50_000L));
		Transaction firstDeposit = transactionRepository.saveAndFlush(
				transaction(account, "codef-d-1", 50_000L));
		Transaction secondDeposit = transactionRepository.saveAndFlush(
				transaction(account, "codef-d-2", 50_000L));

		transferLinkRepository.saveAndFlush(link(withdrawal, firstDeposit));

		// 입금이 다르다. 두 열을 묶은 복합 UNIQUE라면 이 INSERT가 통과한다. 그러면 출금 하나가
		// 입금 둘과 엮여 제약의 목적이 사라진다(docs/09-db-design.md 3.5절).
		assertThatThrownBy(() -> transferLinkRepository.saveAndFlush(
				link(withdrawal, secondDeposit)))
				.as("출금 열의 단독 UNIQUE가 없다. 복합 UNIQUE로 바뀌었을 가능성이 높다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("uq_transfer_links_withdrawal");
	}

	@Test
	@DisplayName("하나의 입금은 하나의 출금과만 엮인다 — 입금 열 단독 UNIQUE")
	void oneDepositCannotLinkToTwoWithdrawals() {
		Account account = givenAccount("link-d-1");
		Transaction firstWithdrawal = transactionRepository.saveAndFlush(
				transaction(account, "codef-w-2", 50_000L));
		Transaction secondWithdrawal = transactionRepository.saveAndFlush(
				transaction(account, "codef-w-3", 50_000L));
		Transaction deposit = transactionRepository.saveAndFlush(
				transaction(account, "codef-d-3", 50_000L));

		transferLinkRepository.saveAndFlush(link(firstWithdrawal, deposit));

		// 출금이 다르다. 복합 UNIQUE라면 여기도 통과한다.
		assertThatThrownBy(() -> transferLinkRepository.saveAndFlush(
				link(secondWithdrawal, deposit)))
				.as("입금 열의 단독 UNIQUE가 없다. 복합 UNIQUE로 바뀌었을 가능성이 높다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("uq_transfer_links_deposit");
	}

	@Test
	@DisplayName("서로 다른 거래 쌍은 얼마든지 연결할 수 있다")
	void distinctTransactionPairsCanBeLinked() {
		Account account = givenAccount("link-ok-1");
		transferLinkRepository.saveAndFlush(link(
				transactionRepository.saveAndFlush(transaction(account, "codef-w-4", 10_000L)),
				transactionRepository.saveAndFlush(transaction(account, "codef-d-4", 10_000L))));

		// 유니크가 (link_status)처럼 엉뚱한 열로 옮겨가면 여기서 깨진다.
		TransferLink second = transferLinkRepository.saveAndFlush(link(
				transactionRepository.saveAndFlush(transaction(account, "codef-w-5", 10_000L)),
				transactionRepository.saveAndFlush(transaction(account, "codef-d-5", 10_000L))));

		assertThat(second.getId()).isNotNull();
	}

	@Test
	@DisplayName("정의되지 않은 link_status는 저장할 수 없다 — CHECK 제약")
	void undefinedLinkStatusIsRejected() {
		Account account = givenAccount("link-check-1");
		Transaction withdrawal = transactionRepository.saveAndFlush(
				transaction(account, "codef-w-6", 10_000L));
		Transaction deposit = transactionRepository.saveAndFlush(
				transaction(account, "codef-d-6", 10_000L));
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO transfer_links (withdrawal_transaction_id, deposit_transaction_id, "
						+ "link_status) VALUES (?, ?, ?)",
				withdrawal.getId(), deposit.getId(), "PENDING"))
				.as("CHECK (link_status IN (...))가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("ck_transfer_links_link_status");
	}

	@Test
	@DisplayName("사용자가 확인한 연결도 저장된다 — link_status CHECK가 'AUTO'만 남지 않았다")
	void userConfirmedLinkStatusIsAllowed() {
		// 거부 케이스만 보면 CHECK 목록에서 'USER_CONFIRMED'가 빠져도 통과한다. 그 스키마는
		// 사용자가 이체로 확인하는 동작(T-029)을 통째로 막는다.
		Account account = givenAccount("link-check-2");
		TransferLink saved = transferLinkRepository.saveAndFlush(new TransferLink(
				transactionRepository.saveAndFlush(transaction(account, "codef-w-8", 10_000L)),
				transactionRepository.saveAndFlush(transaction(account, "codef-d-8", 10_000L)),
				LinkStatus.USER_CONFIRMED,
				null,
				OffsetDateTime.now(AppZone.clock()),
				OffsetDateTime.now(AppZone.clock())));

		assertThat(transferLinkRepository.findById(saved.getId()))
				.get()
				.satisfies(found -> {
					assertThat(found.getLinkStatus()).isEqualTo(LinkStatus.USER_CONFIRMED);
					assertThat(found.getConfirmedAt())
							.as("사용자 확인 시각이 저장되지 않았다")
							.isNotNull();
					assertThat(found.getMatchReason()).isNull();
				});
	}

	@Test
	@DisplayName("출금 거래를 지우면 이체 연결도 함께 지워진다 — 출금 쪽 ON DELETE CASCADE")
	void deletingWithdrawalTransactionCascadesToTransferLink() {
		assertDeletingLinkedTransactionCascades("link-cascade-w", true);
	}

	@Test
	@DisplayName("입금 거래를 지워도 이체 연결이 함께 지워진다 — 입금 쪽 ON DELETE CASCADE")
	void deletingDepositTransactionCascadesToTransferLink() {
		// 같은 제약이 두 컬럼에 걸려 있다. 출금만 지우면 입금 쪽 FK의 ON DELETE 규칙은 한 번도
		// 실행되지 않아, 입금 쪽 CASCADE를 떼도 아무도 모른다. 그 스키마에서는 입금 거래 삭제가
		// FK 위반으로 거부된다.
		assertDeletingLinkedTransactionCascades("link-cascade-d", false);
	}

	/**
	 * 연결된 거래 한쪽을 지우면 {@code transfer_links} 행이 따라 지워지는지 본다.
	 *
	 * @param deleteWithdrawal true면 출금 거래를, false면 입금 거래를 지운다. 어느 쪽 FK가
	 *     깨졌는지 실패 메시지에서 구분되도록 단언 설명에 그대로 실어 보낸다
	 */
	private void assertDeletingLinkedTransactionCascades(String prefix, boolean deleteWithdrawal) {
		Account account = givenAccount(prefix);
		Transaction withdrawal = transactionRepository.saveAndFlush(
				transaction(account, prefix + "-w", 10_000L));
		Transaction deposit = transactionRepository.saveAndFlush(
				transaction(account, prefix + "-d", 10_000L));
		TransferLink saved = transferLinkRepository.saveAndFlush(link(withdrawal, deposit));

		Transaction target = deleteWithdrawal ? withdrawal : deposit;
		String side = deleteWithdrawal ? "withdrawal_transaction_id" : "deposit_transaction_id";

		// CASCADE가 없으면 이 DELETE 자체가 FK 위반으로 거부된다.
		new JdbcTemplate(dataSource).update(
				"DELETE FROM transactions WHERE id = ?", target.getId());

		assertThat(count("SELECT count(*) FROM transfer_links WHERE id = ?", saved.getId()))
				.as("transfer_links.%s FK에 ON DELETE CASCADE가 없다. 그쪽 거래 삭제가 막힌다", side)
				.isZero();
	}

	@Test
	@DisplayName("설계한 인덱스가 실제로 만들어져 있다 — 종류·열 구성·정렬 방향·부분 조건까지")
	void designedIndexesExist() {
		// 유니크 제약이 만드는 인덱스도 pg_indexes에 나온다.
		assertIndex("transactions", "uq_transactions_account_codef_txn_id", "btree",
				"account_id, codef_transaction_id", null);

		// DESC가 빠지면 indexdef에서 그 토큰이 사라져 여기서 깨진다. 최신순 목록 조회가
		// 역방향 스캔으로 돌게 된다.
		assertIndex("transactions", "ix_transactions_user_transacted_at", "btree",
				"user_id, transacted_at DESC", null);
		assertIndex("transactions", "ix_transactions_user_transacted_at_category", "btree",
				"user_id, transacted_at, category_id", null);
		assertIndex("transactions", "ix_transactions_account_transacted_at", "btree",
				"account_id, transacted_at", null);
		assertIndex("transactions", "ix_transactions_account_amount_transacted_at", "btree",
				"account_id, amount, transacted_at", null);

		// 자기참조 FK의 자식 컬럼 인덱스. 이것이 없으면 거래를 한 행 지울 때마다
		// "이 행을 가리키는 환불 거래가 있나"를 확인하려고 transactions 전체를 훑는다.
		// 계좌 해제·탈퇴가 대량 삭제라 여기서 크게 드러난다(측정: 2000건 삭제 3,005ms -> 8ms).
		assertIndex("transactions", "ix_transactions_linked_refund", "btree",
				"linked_refund_transaction_id", null);

		// 부분 인덱스다. WHERE 절이 빠지면 전체 거래를 색인하는 다른 인덱스가 되는데, 이름과
		// 열 구성은 그대로라 조건을 함께 보지 않으면 그 변이를 통과시킨다.
		assertIndex("transactions", "ix_transactions_transfer_status_pending", "btree",
				"transfer_status",
				"((transfer_status)::text = 'PENDING_CONFIRM'::text)");

		assertIndex("transfer_links", "uq_transfer_links_withdrawal", "btree",
				"withdrawal_transaction_id", null);
		assertIndex("transfer_links", "uq_transfer_links_deposit", "btree",
				"deposit_transaction_id", null);
	}

	@Test
	@DisplayName("컬럼 타입이 설계와 일치한다 — udt_name과 VARCHAR 길이까지")
	void columnTypesMatchDesign() {
		// amount를 int4로, category_id를 int2 대신 int4로 바꿔도 Hibernate validate와 제약
		// 테스트는 전부 통과한다(T-013에서 겪은 것과 같은 구멍).
		assertColumnType("transactions", "amount", "int8", null);
		assertColumnType("transactions", "category_id", "int2", null);
		assertColumnType("transactions", "linked_refund_transaction_id", "int8", null);
		assertColumnType("transactions", "transacted_at", "timestamptz", null);

		// VARCHAR는 길이까지 본다. udt_name은 VARCHAR(10)이든 VARCHAR(255)든 'varchar'라
		// 길이를 줄이는 변이(거래 식별자가 잘려 들어오는 스키마)를 udt_name만으로는 못 잡는다.
		assertColumnType("transactions", "codef_transaction_id", "varchar", 255);
		assertColumnType("transactions", "txn_type", "varchar", 10);
		assertColumnType("transactions", "transfer_status", "varchar", 20);
		assertColumnType("transactions", "memo", "varchar", 255);

		assertColumnType("transfer_links", "confirmed_at", "timestamptz", null);
		assertColumnType("transfer_links", "link_status", "varchar", 20);
		assertColumnType("transfer_links", "match_reason", "varchar", 255);
	}

	@Test
	@DisplayName("category_id는 categories를 참조한다 — 없는 카테고리는 저장할 수 없다")
	void categoryForeignKeyIsEnforced() {
		// REFERENCES categories (id)를 통째로 지워도 다른 단언은 전부 통과한다. 자동 분류가
		// 엉뚱한 카테고리 ID를 써도 DB가 받아주는 스키마가 된다.
		Account account = givenAccount("txn-fk-1");
		JdbcTemplate jdbc = new JdbcTemplate(dataSource);

		assertThatThrownBy(() -> jdbc.update(
				"INSERT INTO transactions (user_id, account_id, codef_transaction_id, "
						+ "transacted_at, amount, txn_type, initial_classification_source, "
						+ "category_id) VALUES (?, ?, ?, now(), ?, ?, ?, ?)",
				account.getUser().getId(), account.getId(), "codef-fk-1", 1_000L,
				"EXPENSE", "UNCLASSIFIED", (short) 1234))
				.as("category_id에 categories FK가 없다")
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining("transactions_category_id_fkey");
	}

	@Test
	@DisplayName("NOT NULL 구성이 설계와 정확히 일치한다 — 빠진 것도 더 붙은 것도 없다")
	void nullabilityMatchesDesign() {
		assertNullability("transactions", Map.ofEntries(
				entry("id", "NO"),
				entry("user_id", "NO"),
				entry("account_id", "NO"),
				entry("codef_transaction_id", "NO"),
				entry("transacted_at", "NO"),
				entry("amount", "NO"),
				entry("merchant", "YES"),  // 코드에프가 거래처를 주지 않는 거래가 있다
				entry("txn_type", "NO"),
				entry("category_id", "NO"),  // 미매칭이면 99가 들어간다. null이 아니다
				entry("initial_classification_source", "NO"),
				entry("transfer_status", "NO"),
				entry("refund_status", "NO"),
				entry("linked_refund_transaction_id", "YES"),  // 환불 연결이 없는 거래가 대부분이다
				entry("memo", "YES"),
				entry("created_at", "NO"),
				entry("updated_at", "NO")));

		assertNullability("transfer_links", Map.ofEntries(
				entry("id", "NO"),
				entry("withdrawal_transaction_id", "NO"),
				entry("deposit_transaction_id", "NO"),
				entry("link_status", "NO"),
				entry("match_reason", "YES"),
				entry("confirmed_at", "YES"),  // 자동 연결만 된 상태에서는 null이다
				entry("created_at", "NO")));
	}

	@Test
	@DisplayName("수집 직후 거래는 이체도 환불도 아니고 메모·연결이 비어 있다")
	void newTransactionStartsWithExpectedDefaults() {
		Transaction saved = transactionRepository.saveAndFlush(
				transaction(givenAccount("txn-init-1"), "codef-init", 1_000L));

		assertThat(transactionRepository.findById(saved.getId()))
				.get()
				.satisfies(found -> {
					assertThat(found.getTransferStatus()).isEqualTo(TransferStatus.NONE);
					assertThat(found.getRefundStatus()).isEqualTo(RefundStatus.NONE);
					assertThat(found.getLinkedRefundTransaction()).isNull();
					assertThat(found.getMemo()).isNull();
					assertThat(found.getInitialClassificationSource())
							.isEqualTo(ClassificationSource.UNCLASSIFIED);
					assertThat(found.getCategory().getId()).isEqualTo(UNCLASSIFIED_CATEGORY_ID);
				});
	}

	/** {@code transactions}에 기본값만 채운 raw INSERT를 던져 CHECK 위반을 확인한다. */
	private void assertRawInsertRejected(
			Account account, String codefTransactionId, Map<String, String> overrides,
			String constraintName) {
		assertThatThrownBy(() -> rawInsert(account, codefTransactionId, overrides))
				.as("CHECK 제약 %s가 없다", constraintName)
				.isInstanceOf(DataIntegrityViolationException.class)
				.rootCause()
				.hasMessageContaining(constraintName);
	}

	/**
	 * 명세에 있는 값이 실제로 저장되는지 확인한다.
	 *
	 * <p>거부 케이스의 짝이다. CHECK 목록에서 값을 빼는 변이는 거부 단언으로는 잡히지 않는다.
	 * 저장 후 값을 되읽어, 들어갔지만 다른 값으로 바뀌는 경우까지 함께 본다.
	 */
	private void assertRawInsertAccepted(
			Account account, String codefTransactionId, Map<String, String> overrides) {
		String column = overrides.keySet().iterator().next();
		String expected = overrides.get(column);

		rawInsert(account, codefTransactionId, overrides);

		String stored = new JdbcTemplate(dataSource).queryForObject(
				"SELECT " + column + " FROM transactions WHERE codef_transaction_id = ?",
				String.class, codefTransactionId);
		assertThat(stored)
				.as("%s = '%s'가 저장되지 않았다. CHECK 목록에서 이 값이 빠졌을 가능성이 높다",
						column, expected)
				.isEqualTo(expected);
	}

	private void rawInsert(
			Account account, String codefTransactionId, Map<String, String> overrides) {
		String txnType = overrides.getOrDefault("txn_type", "EXPENSE");
		String source = overrides.getOrDefault("initial_classification_source", "UNCLASSIFIED");
		String transferStatus = overrides.getOrDefault("transfer_status", "NONE");
		String refundStatus = overrides.getOrDefault("refund_status", "NONE");

		new JdbcTemplate(dataSource).update(
				"INSERT INTO transactions (user_id, account_id, codef_transaction_id, "
						+ "transacted_at, amount, txn_type, initial_classification_source, "
						+ "transfer_status, refund_status) VALUES (?, ?, ?, now(), ?, ?, ?, ?, ?)",
				account.getUser().getId(), account.getId(), codefTransactionId, 1_000L,
				txnType, source, transferStatus, refundStatus);
	}

	/**
	 * 컬럼의 실제 타입을 못 박는다.
	 *
	 * <p>{@code data_type}이 아니라 {@code udt_name}을 본다. {@code SMALLINT}와
	 * {@code INTEGER}는 {@code data_type}이 각각 {@code smallint}·{@code integer}로 갈리지만,
	 * Hibernate validate도 PostgreSQL의 FK 비교도 둘을 구분하지 않아 바뀌어도 아무도 잡지
	 * 못한다(T-013).
	 */
	private void assertColumnType(
			String tableName, String columnName, String expectedUdtName, Integer expectedLength) {
		Map<String, Object> column = new JdbcTemplate(dataSource).queryForMap(
				"SELECT udt_name, character_maximum_length FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND table_name = ? AND column_name = ?",
				tableName, columnName);

		assertThat(column.get("udt_name"))
				.as("%s.%s의 타입이 설계와 다르다", tableName, columnName)
				.isEqualTo(expectedUdtName);
		assertThat(column.get("character_maximum_length"))
				.as("%s.%s의 길이가 설계와 다르다", tableName, columnName)
				.isEqualTo(expectedLength);
	}

	/**
	 * 테이블의 NOT NULL 구성이 설계와 정확히 일치하는지 본다
	 * ({@code CategorySchemaTest.assertNullability}와 같은 취지).
	 *
	 * <p>컬럼 맵을 통째로 비교해 {@code NOT NULL}이 빠지는 것뿐 아니라 없던 컬럼이 늘어나는
	 * 것까지 함께 잡는다.
	 *
	 * @param expected 컬럼명 → {@code "NO"}(NOT NULL) 또는 {@code "YES"}(null 허용)
	 */
	private void assertNullability(String tableName, Map<String, String> expected) {
		List<Map<String, Object>> columns = new JdbcTemplate(dataSource).queryForList(
				"SELECT column_name, is_nullable FROM information_schema.columns "
						+ "WHERE table_schema = 'public' AND table_name = ?",
				tableName);

		Map<String, String> actual = columns.stream().collect(Collectors.toMap(
				column -> (String) column.get("column_name"),
				column -> (String) column.get("is_nullable")));

		assertThat(actual)
				.as("%s 테이블의 NOT NULL 구성이 설계와 다르다", tableName)
				.containsExactlyInAnyOrderEntriesOf(expected);
	}

	/**
	 * 인덱스가 설계대로 존재하는지 본다.
	 *
	 * <p>{@code CategorySchemaTest.assertIndex}가 종류까지 봤지만 그 헬퍼는
	 * {@code endsWith("USING ... (열)")}이라 <b>부분 인덱스에 쓸 수 없다.</b> 부분 인덱스의
	 * {@code indexdef}는 열 목록 뒤에 {@code WHERE ...}가 더 붙어 정의가 맞아도 실패한다.
	 * DESC는 열 목록 안에 {@code transacted_at DESC}로 들어가므로 열 인자에 그대로 적는다.
	 *
	 * @param method {@code USING} 뒤에 나와야 하는 인덱스 종류(소문자)
	 * @param expectedColumns {@code indexdef} 괄호 안에 그대로 나타나야 하는 열 목록
	 * @param expectedPredicate 부분 인덱스의 조건. PostgreSQL이 정규화한 모양 그대로 적는다.
	 *     부분 인덱스가 아니면 null이며, 이때 {@code WHERE} 절이 붙어 있으면 실패한다
	 */
	private void assertIndex(
			String tableName, String indexName, String method, String expectedColumns,
			String expectedPredicate) {
		List<String> definitions = new JdbcTemplate(dataSource).queryForList(
				"SELECT indexdef FROM pg_indexes "
						+ "WHERE schemaname = 'public' AND tablename = ? AND indexname = ?",
				String.class, tableName, indexName);

		assertThat(definitions)
				.as("%s 테이블에 인덱스 %s가 없다", tableName, indexName)
				.hasSize(1);

		String expectedTail = "USING " + method + " (" + expectedColumns + ")"
				+ (expectedPredicate == null ? "" : " WHERE " + expectedPredicate);
		assertThat(definitions.get(0))
				.as("인덱스 %s의 종류·열 구성·정렬 방향·부분 조건 중 하나가 설계와 다르다", indexName)
				// endsWith라서 조건이 더 붙거나 빠지는 것이 양쪽 다 걸린다.
				.endsWith(expectedTail);
	}

	private User givenUser(String providerUserId) {
		return userRepository.saveAndFlush(
				new User(AuthProvider.KAKAO, providerUserId, null, OffsetDateTime.now(AppZone.clock())));
	}

	private Account givenAccount(String providerUserId) {
		return givenAccount(givenUser(providerUserId), "110-****-1234");
	}

	private Account givenAccount(User user, String maskedAccountNo) {
		return accountRepository.saveAndFlush(new Account(
				user,
				"004",
				"connected-id-" + maskedAccountNo,
				maskedAccountNo,
				null,
				null,
				OffsetDateTime.now(AppZone.clock())));
	}

	private Transaction transaction(Account account, String codefTransactionId, long amount) {
		Category unclassified = categoryRepository.findById(UNCLASSIFIED_CATEGORY_ID).orElseThrow();
		return new Transaction(
				account.getUser(),
				account,
				codefTransactionId,
				OffsetDateTime.now(AppZone.clock()),
				amount,
				"테스트가맹점",
				TxnType.EXPENSE,
				unclassified,
				ClassificationSource.UNCLASSIFIED,
				OffsetDateTime.now(AppZone.clock()));
	}

	private TransferLink link(Transaction withdrawal, Transaction deposit) {
		return new TransferLink(
				withdrawal, deposit, LinkStatus.AUTO, "금액 일치·3분 차이", null,
				OffsetDateTime.now(AppZone.clock()));
	}

	private int count(String sql, Long id) {
		Integer count = new JdbcTemplate(dataSource).queryForObject(sql, Integer.class, id);
		return count == null ? 0 : count;
	}
}
