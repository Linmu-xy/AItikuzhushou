package com.tikuzhushou.project;

import java.math.BigDecimal;
import java.util.*;

/** Deterministic checks, not a substitute for subject expertise or item statistics. */
public final class QuestionQualityChecks {
  private QuestionQualityChecks() {}
  public record Issue(String code,String severity,String message) {}
  public static List<Issue> inspect(Map<String,Object> q,String type,int points) {
    List<Issue> issues=new ArrayList<>();
    if(text(q.get("stem")).isBlank()) issues.add(error("STEM","题干为空"));
    if(text(q.get("answer")).isBlank()) issues.add(error("ANSWER","参考答案为空"));
    if(text(q.get("analysis")).isBlank()) issues.add(warn("ANALYSIS","补充解析，说明关键推断与错误解法"));
    if(type.endsWith("CHOICE")) {
      List<String> options=options(q.get("options"));
      if(options.size()!=4 || options.stream().anyMatch(String::isBlank)) issues.add(error("OPTIONS","选择题需要完整的 A–D 四个选项"));
      if(new HashSet<>(options.stream().map(QuestionQualityChecks::normalized).toList()).size()!=options.size()) issues.add(error("DUPLICATE_OPTIONS","存在内容相同的选项"));
      String answer=text(q.get("answer"));
      if("SINGLE_CHOICE".equals(type) && !answer.matches("[A-D]")) issues.add(error("ANSWER_FORMAT","单选题答案须为 A–D 中的一个字母"));
      if("MULTIPLE_CHOICE".equals(type)) {
        var letters=Arrays.asList(answer.split("[、,，]"));
        if(!answer.matches("[A-D](?:[、,，][A-D])+") || new HashSet<>(letters).size()!=letters.size()) issues.add(error("ANSWER_FORMAT","多选题需要至少两个互不重复的 A–D 答案"));
      }
    }
    if("TRUE_FALSE".equals(type) && !Set.of("正确","错误").contains(text(q.get("answer")))) issues.add(error("ANSWER_FORMAT","判断题答案须为正确或错误"));
    boolean subjective=!Set.of("SINGLE_CHOICE","MULTIPLE_CHOICE","TRUE_FALSE","FILL_BLANK").contains(type);
    if(q.containsKey("scoringItems") && !(q.get("scoringItems") instanceof List<?>)) issues.add(error("RUBRIC_FORMAT","评分点应为列表"));
    if(q.get("scoringItems") instanceof List<?> rows && !rows.isEmpty()) {
      BigDecimal total=BigDecimal.ZERO; boolean valid=true;
      for(Object raw:rows) {
        if(!(raw instanceof Map<?,?> row) || text(row.get("criterion")).isBlank()) {valid=false;continue;}
        try {
          BigDecimal score=new BigDecimal(text(row.get("points")));
          if(score.signum()<=0 || score.scale()>2) valid=false;
          total=total.add(score);
        } catch(NumberFormatException e) {valid=false;}
      }
      if(!valid) issues.add(error("RUBRIC_ITEM","每个评分点须有说明及正分值，最多两位小数"));
      if(total.compareTo(BigDecimal.valueOf(points))!=0) issues.add(error("RUBRIC_TOTAL","评分点合计 "+total.stripTrailingZeros().toPlainString()+" 分，与本题 "+points+" 分不一致"));
    } else if(subjective) {
      issues.add(text(q.get("scoringRubric")).isBlank()?error("RUBRIC_MISSING","主观题缺少评分细则"):warn("RUBRIC_TEXT","当前是文字评分细则；拆成评分点后可自动核对总分"));
    }
    return List.copyOf(issues);
  }
  public static List<String> options(Object raw) {
    if(raw instanceof Map<?,?> values) return List.of("A","B","C","D").stream().map(k->text(values.get(k))).toList();
    if(text(raw).isBlank()) return List.of();
    return Arrays.stream(text(raw).split("\\s*\\|\\s*",-1)).map(v->v.replaceFirst("^[A-D][.、．:：)）]\\s*","").trim()).toList();
  }
  public static double similarity(String left,String right) {
    String a=normalized(left),b=normalized(right);
    if(a.isBlank() || b.isBlank()) return 0;
    if(a.equals(b)) return 1;
    Set<String> x=grams(a),y=grams(b); int overlap=0;
    for(String value:x) if(y.contains(value)) overlap++;
    return 2.0*overlap/Math.max(1,x.size()+y.size());
  }
  private static Set<String> grams(String value) {
    Set<String> result=new HashSet<>(); for(int i=0;i<value.length()-1;i++) result.add(value.substring(i,i+2)); return result;
  }
  private static String normalized(String value) {return value.toLowerCase(Locale.ROOT).replaceAll("[\\p{P}\\p{Z}\\s]","");}
  private static Issue error(String code,String text){return new Issue(code,"ERROR",text);}
  private static Issue warn(String code,String text){return new Issue(code,"WARNING",text);}
  private static String text(Object value){return Objects.toString(value,"").trim();}
}
