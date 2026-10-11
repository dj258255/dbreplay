package io.dbreplay.explore;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 배속을 바꿔 가며 틀어 대상이 버티는 가장 높은 배속을 찾는다.
 *
 * <p>먼저 배속을 두 배씩 올려 알람이 울리는 경계를 찾고, 그다음 알람이 안 울린 최대 배속과 울린 최소 배속
 * 사이를 반씩 좁힌다. 메이투안 글의 빠른 탐색과 정밀 보정이다. 미리 정한 정밀도에 닿으면 끝낸다.
 */
public final class Explorer {

	/**
	 * @param startSpeed 첫 라운드의 배속
	 * @param maxSpeed   이 배속에서도 버티면 더 올리지 않는다
	 * @param precision  버틴 배속과 못 버틴 배속의 차이가 버틴 배속의 이 비율 이하가 되면 끝낸다
	 * @param maxRounds  라운드 수의 상한
	 */
	public record Options(double startSpeed, double maxSpeed, double precision, int maxRounds) {

		public static Options defaults() {
			return new Options(1, 64, 0.1, 12);
		}
	}

	/** 한 라운드: 데이터를 되돌리고 그 배속으로 한 번 튼다. */
	@FunctionalInterface
	public interface RoundRunner {

		RoundData run(double speed) throws Exception;
	}

	public record Round(int number, double speed, RoundData data, RoundJudge.Verdict verdict) {
	}

	/**
	 * @param ceilingSpeed 버틴 가장 높은 배속. 첫 라운드부터 못 버텼으면 0
	 * @param stoppedBy    위쪽 경계를 만든 판정. 알람이면 OVERLOADED, 재생기의 한계면 DISTORTED.
	 *                     못 버틴 라운드가 없었으면 null이고, 그때 상한은 ceilingSpeed 이상이라는 것만 안다
	 */
	public record Result(List<Round> rounds, double ceilingSpeed, RoundJudge.State stoppedBy) {
	}

	private Explorer() {
	}

	public static Result explore(RoundRunner runner, RoundJudge judge, Options options, Consumer<Round> onRound)
			throws Exception {
		List<Round> rounds = new ArrayList<>();
		double held = 0;
		double failed = Double.POSITIVE_INFINITY;
		RoundJudge.State stoppedBy = null;

		// 빠른 탐색: 두 배씩 올린다
		double speed = options.startSpeed();
		while (rounds.size() < options.maxRounds()) {
			Round round = play(runner, judge, rounds, speed, onRound);
			if (round.verdict().state() != RoundJudge.State.HELD) {
				failed = speed;
				stoppedBy = round.verdict().state();
				break;
			}
			held = speed;
			if (speed >= options.maxSpeed()) {
				break;
			}
			speed = Math.min(speed * 2, options.maxSpeed());
		}

		// 정밀 보정: 사이를 반씩 좁힌다
		while (held > 0 && Double.isFinite(failed) && (failed - held) / held > options.precision()
				&& rounds.size() < options.maxRounds()) {
			double middle = (held + failed) / 2;
			Round round = play(runner, judge, rounds, middle, onRound);
			if (round.verdict().state() == RoundJudge.State.HELD) {
				held = middle;
			}
			else {
				failed = middle;
				stoppedBy = round.verdict().state();
			}
		}
		return new Result(rounds, held, stoppedBy);
	}

	private static Round play(RoundRunner runner, RoundJudge judge, List<Round> rounds, double speed,
			Consumer<Round> onRound) throws Exception {
		RoundData data = runner.run(speed);
		Round round = new Round(rounds.size() + 1, speed, data, judge.judge(data));
		rounds.add(round);
		onRound.accept(round);
		return round;
	}
}
