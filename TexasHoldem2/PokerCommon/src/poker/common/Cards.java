package poker.common;

import java.util.ArrayList;
import java.util.List;

/**
 * [공통 / 카드 표현] 카드 52장을 정수 0~51로 관리하고, 통신할 때 두 글자 코드로 바꾼다.
 * 0~12는 스페이드, 13~25는 하트, 26~38은 다이아, 39~51은 클로버다.
 * 한 무늬 안에서는 2,3,...,10,J,Q,K,A 순서다. A의 족보 비교 숫자는 14다.
 * 예: 0 → 2S, 12 → AS, 25 → AH. T는 두 글자 코드를 유지하기 위한 숫자 10의 표기다.
 */
public final class Cards {
    // 문자열의 위치가 카드 숫자/무늬의 인덱스와 대응한다. charAt(index)로 해당 문자를 꺼낸다.
    private static final String RANKS = "23456789TJQKA";
    private static final String SUITS = "SHDC";
    // 전부 static 메서드이므로 객체를 만들 필요가 없는 도구 클래스다.
    private Cards() { }
    // % 13은 같은 무늬 안의 위치(0~12)를 구한다. 여기에 2를 더하면 카드 숫자 2~14가 된다.
    public static int rank(int card) { return card % 13 + 2; }
    // int 나눗셈 / 13은 몫만 남겨 무늬 번호 0~3을 구한다.
    public static int suit(int card) { return card / 13; }
    // 정수 카드 ID를 "AS", "TH" 같은 코드로 바꾼다. 앞의 빈 문자열은 문자 두 개를 이어 붙이기 위함이다.
    public static String code(int card) {
        return "" + RANKS.charAt(card % 13) + SUITS.charAt(card / 13);
    }
    // code()의 반대 변환이다. 각 문자의 위치를 찾고 suit * 13 + rankIndex로 원래 ID를 복원한다.
    public static int parse(String code) {
        if (code.length() != 2) throw new IllegalArgumentException("invalid card");
        int r = RANKS.indexOf(code.charAt(0));
        int s = SUITS.indexOf(code.charAt(1));
        if (r < 0 || s < 0) throw new IllegalArgumentException("invalid card");
        return s * 13 + r;
    }
    // 여러 카드를 쉼표로 묶는다. 예: [12, 24] → "AS,KH". 빈 목록은 빈 문자열이 된다.
    public static String encode(List<Integer> cards) {
        StringBuilder out = new StringBuilder();
        for (int card : cards) {
            if (out.length() > 0) out.append(',');
            out.append(code(card));
        }
        return out.toString();
    }
    // 쉼표로 구분된 카드 코드를 다시 정수 목록으로 바꾼다. 빈 문자열이면 빈 목록을 반환한다.
    public static List<Integer> decode(String value) {
        List<Integer> cards = new ArrayList<Integer>();
        if (value != null && !value.isEmpty())
            for (String code : value.split(",")) cards.add(parse(code));
        return cards;
    }
}
