package github.lms.lemuel.category.util;

import github.lms.lemuel.category.domain.exception.CategoryInvariantViolationException;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.HashMap;
import java.util.Map;

/**
 * Slug 생성 유틸리티
 * 한글 및 다국어 지원, URL-safe 문자열 생성
 */
@Component
public class SlugGenerator {

    private static final Map<Character, String> KOREAN_TO_ROMAN = new HashMap<>();

    static {
        // 한글 자음 로마자 매핑
        KOREAN_TO_ROMAN.put('ㄱ', "g");
        KOREAN_TO_ROMAN.put('ㄴ', "n");
        KOREAN_TO_ROMAN.put('ㄷ', "d");
        KOREAN_TO_ROMAN.put('ㄹ', "r");
        KOREAN_TO_ROMAN.put('ㅁ', "m");
        KOREAN_TO_ROMAN.put('ㅂ', "b");
        KOREAN_TO_ROMAN.put('ㅅ', "s");
        KOREAN_TO_ROMAN.put('ㅇ', "");
        KOREAN_TO_ROMAN.put('ㅈ', "j");
        KOREAN_TO_ROMAN.put('ㅊ', "ch");
        KOREAN_TO_ROMAN.put('ㅋ', "k");
        KOREAN_TO_ROMAN.put('ㅌ', "t");
        KOREAN_TO_ROMAN.put('ㅍ', "p");
        KOREAN_TO_ROMAN.put('ㅎ', "h");
    }

    /**
     * 문자열을 slug로 변환
     * 예: "전자제품" -> "electronics", "컴퓨터 및 주변기기" -> "computer-accessories"
     */
    public String generate(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new CategoryInvariantViolationException("Input string cannot be empty");
        }

        String slug = input.toLowerCase().trim();

        // Unicode normalize (특수문자 제거)
        slug = Normalizer.normalize(slug, Normalizer.Form.NFD);
        slug = slug.replaceAll("\\p{M}", ""); // 발음 구별 기호 제거

        // 공백과 특수문자를 하이픈으로 변환
        slug = slug.replaceAll("[\\s_]+", "-");

        // 한글을 로마자로 변환 (간단한 변환)
        slug = transliterateKorean(slug);

        // 허용된 문자만 유지 (소문자, 숫자, 하이픈)
        slug = slug.replaceAll("[^a-z0-9-]", "");

        // 연속된 하이픈을 하나로
        slug = slug.replaceAll("-+", "-");

        // 앞뒤 하이픈 제거
        // 그룹으로 의도를 명시한다(S5850): "(^-) 또는 (-$)" 이지 "^(-|-)$" 가 아니다.
        // 동작은 종전과 같다 — 위에서 연속 하이픈을 하나로 접었으므로 각 끝에서 1개만 지우면 된다.
        slug = slug.replaceAll("(?:^-)|(?:-$)", "");

        if (slug.isEmpty()) {
            throw new CategoryInvariantViolationException("Generated slug is empty. Input may contain only special characters.");
        }

        return slug;
    }

    /**
     * 한글 초성, 중성, 종성을 로마자로 변환 (간소화된 버전)
     */
    private String transliterateKorean(String input) {
        StringBuilder result = new StringBuilder();

        for (char ch : input.toCharArray()) {
            if (ch >= '가' && ch <= '힣') {
                // 한글 음절 분해
                int unicode = ch - 0xAC00;
                int initial = unicode / (21 * 28);
                int medial = (unicode % (21 * 28)) / 28;
                int finale = unicode % 28;

                result.append(getInitialConsonant(initial));
                result.append(getMedialVowel(medial));
                if (finale > 0) {
                    result.append(getFinalConsonant(finale));
                }
            } else {
                result.append(ch);
            }
        }

        return result.toString();
    }

    private String getInitialConsonant(int index) {
        String[] consonants = {"g", "kk", "n", "d", "tt", "r", "m", "b", "pp", "s", "ss", "", "j", "jj", "ch", "k", "t", "p", "h"};
        return consonants[index];
    }

    private String getMedialVowel(int index) {
        String[] vowels = {"a", "ae", "ya", "yae", "eo", "e", "yeo", "ye", "o", "wa", "wae", "oe", "yo", "u", "wo", "we", "wi", "yu", "eu", "ui", "i"};
        return vowels[index];
    }

    private String getFinalConsonant(int index) {
        String[] consonants = {"", "g", "kk", "gs", "n", "nj", "nh", "d", "l", "lg", "lm", "lb", "ls", "lt", "lp", "lh", "m", "b", "bs", "s", "ss", "ng", "j", "ch", "k", "t", "p", "h"};
        return consonants[index];
    }

    /**
     * 부모 slug와 현재 이름으로 계층형 slug 생성
     * 예: "electronics" + "컴퓨터" -> "electronics-computer"
     */
    public String generateWithParent(String parentSlug, String name) {
        String childSlug = generate(name);
        if (parentSlug == null || parentSlug.trim().isEmpty()) {
            return childSlug;
        }
        return parentSlug + "-" + childSlug;
    }

    /**
     * slug 유효성 검증
     */
    public boolean isValid(String slug) {
        if (slug == null || slug.trim().isEmpty()) {
            return false;
        }
        return slug.matches("^[a-z0-9-]+$") && !slug.startsWith("-") && !slug.endsWith("-");
    }
}
