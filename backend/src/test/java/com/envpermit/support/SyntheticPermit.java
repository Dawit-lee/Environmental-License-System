package com.envpermit.support;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * Synthetic permit conditions for the measurements (docs/measurements/): made from the word lists below only,
 * no company document. One condition per (facility, pollutant) pair, so a pair never repeats inside a document
 * and an altered copy of one condition cannot collide with another.
 */
public final class SyntheticPermit {

    public static final List<String> FACILITIES = List.of("소각로 1호기", "소각로 2호기", "보일러", "건조시설", "혐기성 소화조",
            "악취방지시설", "침출수 처리시설", "방류구", "매립가스 포집시설", "저장조", "파쇄시설", "선별시설");
    /** name, unit */
    public static final List<String[]> POLLUTANTS = List.of(new String[] {"먼지", "mg/S㎥"}, new String[] {"일산화탄소", "ppm"},
            new String[] {"질소산화물", "ppm"}, new String[] {"황산화물", "ppm"}, new String[] {"염화수소", "ppm"},
            new String[] {"암모니아", "ppm"}, new String[] {"생물화학적 산소요구량", "mg/L"},
            new String[] {"화학적 산소요구량", "mg/L"}, new String[] {"부유물질", "mg/L"}, new String[] {"총질소", "mg/L"});
    public static final List<String> PERIODS = List.of("월", "분기", "반기", "연");

    public enum Template { LIMIT, MEASURE, KEEP }

    /** One condition; {@link #text()} is the sentence as the document prints it. */
    public record Condition(int facility, int pollutant, Template template, int number, String period, int times, int days) {

        public String text() {
            String f = FACILITIES.get(facility);
            String[] p = POLLUTANTS.get(pollutant);
            return switch (template) {
                case LIMIT -> "%s 배출구의 %s 농도는 %d%s 이하로 유지하여야 한다.".formatted(f, p[0], number, p[1]);
                case MEASURE -> "%s에서 배출되는 %s을(를) %s %d회 이상 측정하고 그 결과를 %d일 이내에 승인기관에 보고하여야 한다."
                        .formatted(f, p[0], period, times, days);
                case KEEP -> "%s의 %s 측정기록은 %d년간 보존하여야 한다.".formatted(f, p[0], number);
            };
        }

        public Condition withNumber(int n) {
            return new Condition(facility, pollutant, template, n, period, times, days);
        }

        public Condition withPeriod(String p) {
            return new Condition(facility, pollutant, template, number, p, times, days);
        }

        public Condition withTemplate(Template t) {
            return new Condition(facility, pollutant, t, number, period, times, days);
        }

        public Condition withTimes(int t) {
            return new Condition(facility, pollutant, template, number, period, t, days);
        }
    }

    private SyntheticPermit() {
    }

    /** Every (facility, pollutant) pair once, shuffled; take a prefix for the document and the rest stays unused. */
    public static List<Condition> conditions(Random rnd) {
        List<Condition> all = new ArrayList<>();
        for (int f = 0; f < FACILITIES.size(); f++) {
            for (int p = 0; p < POLLUTANTS.size(); p++) {
                Template t = Template.values()[rnd.nextInt(3)];
                int number = t == Template.KEEP ? 3 + rnd.nextInt(8) : 10 * (1 + rnd.nextInt(30));
                all.add(new Condition(f, p, t, number, PERIODS.get(rnd.nextInt(PERIODS.size())), 1 + rnd.nextInt(3),
                        10 * (1 + rnd.nextInt(3))));
            }
        }
        Collections.shuffle(all, rnd);
        return all;
    }
}
