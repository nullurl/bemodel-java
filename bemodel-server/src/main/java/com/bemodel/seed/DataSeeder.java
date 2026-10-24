package com.bemodel.seed;

import com.bemodel.datasource.service.DatasourceService;
import com.bemodel.datasource.service.SchemaScanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.Profile;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 演示业务数据生成器 v2：40 名虚构患者的住院医嘱全闭环数据。
 * 闭环链路：入院 → 医嘱（检验/药品/检查） → 计费 → 缴费（预交金/补缴）
 *          → 检验申请+报告(LIS) / 调剂发药(PHARMACY) → 出院结算(CHARGE)。
 * 预埋故障保持不变：LIS v5.2(2026-08-15) 撤销码 X→C，HIS计费适配器 status_map 缺 C，
 * 恰好 5 笔「医嘱已取消/申请已撤销」费用未退（含客诉患者张建国），2 笔升级前 X 撤销已退费作对照。
 * 所有姓名/数据均为虚构，不含身份证、电话、住址等敏感信息。
 * 幂等：患者数 ≥40 且发药记录非空则跳过；否则清空四个产品库重新生成（确定性，可重复）。
 */
@Slf4j
@Component
@Profile("!forgepilot")
@RequiredArgsConstructor
public class DataSeeder implements ApplicationRunner {

    private final DatasourceService datasourceService;
    private final SchemaScanService schemaScanService;
    /** 平台库（bm_* 元数据）的 JDBC：Spring Boot 主数据源，区别于各产品库 */
    private final JdbcTemplate platformJdbc;

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private record Item(String code, String name, double price, String type) {}

    // type: LAB 检验 / DRUG 药品 / EXAM 检查
    private static final Item CBC = new Item("L001", "血常规", 25.00, "LAB");
    private static final Item URINE = new Item("L002", "尿常规", 15.00, "LAB");
    private static final Item LIVER = new Item("L003", "肝功能", 60.00, "LAB");
    private static final Item COAG = new Item("L004", "凝血四项", 80.00, "LAB");
    private static final Item ELECTROLYTE = new Item("L005", "电解质", 35.00, "LAB");
    private static final Item CRP = new Item("L006", "C反应蛋白", 30.00, "LAB");
    private static final Item GLUCOSE = new Item("L007", "空腹血糖", 10.00, "LAB");

    // 药事目录（D009降压药/D010降糖药/D011抗生素 为病案质控规则引用）
    private static final Item[] DRUGS = {
            new Item("D001", "阿莫西林胶囊", 18.50, "DRUG"),
            new Item("D002", "布洛芬缓释胶囊", 22.00, "DRUG"),
            new Item("D003", "0.9%氯化钠注射液", 6.50, "DRUG"),
            new Item("D004", "胰岛素注射液", 45.00, "DRUG"),
            new Item("D005", "维生素C片", 12.00, "DRUG"),
            new Item("D006", "头孢克肟分散片", 28.00, "DRUG"),
            new Item("D007", "奥美拉唑肠溶胶囊", 15.80, "DRUG"),
            new Item("D008", "氨溴索口服液", 19.60, "DRUG"),
            new Item("D009", "硝苯地平缓释片", 16.80, "DRUG"),
            new Item("D010", "盐酸二甲双胍片", 9.50, "DRUG"),
            new Item("D011", "头孢呋辛酯片", 24.00, "DRUG")};
    private static final Item ANTIHYPERTENSIVE = DRUGS[8];  // D009
    private static final Item ANTIBIOTIC = DRUGS[10];       // D011
    // D012 华法林不进 DRUGS 轮转数组（避免扰动既有患者的药品分配），仅用于显式相互作用案例
    private static final Item WARFARIN = new Item("D012", "华法林钠片", 15.00, "DRUG");
    private static final Item PPI = DRUGS[6];               // D007

    private static final Item CT = new Item("R001", "CT胸部平扫", 350.00, "EXAM");
    private static final Item DR = new Item("R002", "DR胸片", 80.00, "EXAM");
    private static final Item US = new Item("R003", "腹部B超", 120.00, "EXAM");

    private static final String[] PHARMACISTS = {"王药师", "李药师", "张药师"};
    private static final String[] CHANNELS = {"现金", "扫码", "医保"};

    private int orderSeq = 0;
    private int feeSeq = 0;
    private int applySeq = 0;
    private int reportSeq = 0;
    private int dispenseSeq = 0;
    private int paySeq = 0;
    private int refundSeq = 0;

    @Override
    public void run(ApplicationArguments args) {
        seedStructuredAxioms();
        JdbcTemplate his = datasourceService.jdbc("DS_HIS");
        Integer cnt = his.queryForObject("SELECT COUNT(*) FROM inpatient", Integer.class);
        JdbcTemplate pharmacy = datasourceService.jdbc("DS_PHARMACY");
        Integer dispenseCnt = pharmacy.queryForObject("SELECT COUNT(*) FROM dispense_record", Integer.class);
        JdbcTemplate opd = datasourceService.jdbc("DS_OPD");
        Integer opdCnt = opd.queryForObject("SELECT COUNT(*) FROM opd_reg", Integer.class);
        JdbcTemplate emr = datasourceService.jdbc("DS_EMR");
        Integer emrCnt = emr.queryForObject("SELECT COUNT(*) FROM emr_record", Integer.class);
        Integer pacsCnt = datasourceService.jdbc("DS_PACS").queryForObject("SELECT COUNT(*) FROM exam_report", Integer.class);
        Integer nurseCnt = datasourceService.jdbc("DS_NURSE").queryForObject("SELECT COUNT(*) FROM nurse_exec", Integer.class);
        Integer drugDictCnt = pharmacy.queryForObject("SELECT COUNT(*) FROM drug_dict", Integer.class);
        Integer reviewCnt = pharmacy.queryForObject("SELECT COUNT(*) FROM presc_review", Integer.class);
        Integer vitalCnt = datasourceService.jdbc("DS_NURSE").queryForObject("SELECT COUNT(*) FROM vital_sign", Integer.class);
        Integer drgCnt = datasourceService.jdbc("DS_EMR").queryForObject("SELECT COUNT(*) FROM drg_group", Integer.class);
        Integer fuCnt = datasourceService.jdbc("DS_EMR").queryForObject("SELECT COUNT(*) FROM followup", Integer.class);
        Integer mOutCnt = datasourceService.jdbc("DS_MATERIAL").queryForObject("SELECT COUNT(*) FROM material_out", Integer.class);
        Integer staffCnt = his.queryForObject("SELECT COUNT(*) FROM staff", Integer.class);
        if (cnt != null && cnt >= 41 && dispenseCnt != null && dispenseCnt > 0
                && opdCnt != null && opdCnt >= 25 && emrCnt != null && emrCnt > 0
                && pacsCnt != null && pacsCnt > 0 && nurseCnt != null && nurseCnt > 0
                && drugDictCnt != null && drugDictCnt >= 12 && reviewCnt != null && reviewCnt > 0
                && vitalCnt != null && vitalCnt > 0 && drgCnt != null && drgCnt > 0
                && fuCnt != null && fuCnt > 0 && mOutCnt != null && mOutCnt > 0
                && staffCnt != null && staffCnt >= 17) {
            log.info("演示数据已是最新版本（住院 {} 名 / 门诊 {} 名 / 病案 {} 份 / 检查报告 {} 份），跳过生成",
                    cnt, opdCnt, emrCnt, pacsCnt);
            return;
        }
        log.info("开始生成全闭环演示数据（虚构数据，含预埋故障）...");
        JdbcTemplate lis = datasourceService.jdbc("DS_LIS");
        JdbcTemplate charge = datasourceService.jdbc("DS_CHARGE");
        JdbcTemplate pacs = datasourceService.jdbc("DS_PACS");
        JdbcTemplate nurse = datasourceService.jdbc("DS_NURSE");
        wipe(his, lis, charge, pharmacy, opd, emr, pacs, nurse);

        List<String[]> patients = patients();
        for (String[] p : patients) {
            his.update("INSERT INTO inpatient(inhos_no,patient_name,sex,age,ward,dept,admit_time,discharge_time,status,doctor) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    p[0], p[1], p[2], Integer.parseInt(p[3]), p[4], p[5], p[6], p[7], p[8], p[9]);
        }

        Map<String, BigDecimal> settleAmount = new HashMap<>();

        for (int i = 0; i < patients.size(); i++) {
            String[] p = patients.get(i);
            // 检验医嘱（已执行，LIS 有申请+报告）
            executedOrder(his, lis, charge, pharmacy, pacs, nurse, p, CBC, 1, settleAmount);
            if (i % 2 == 0) executedOrder(his, lis, charge, pharmacy, pacs, nurse, p, URINE, 1, settleAmount);
            if (i % 3 == 0) executedOrder(his, lis, charge, pharmacy, pacs, nurse, p, LIVER, 2, settleAmount);
            if (i % 7 == 2) executedOrder(his, lis, charge, pharmacy, pacs, nurse, p, COAG, 2, settleAmount);
            if (i % 5 == 1) executedOrder(his, lis, charge, pharmacy, pacs, nurse, p, ELECTROLYTE, 2, settleAmount);
            if (i % 4 == 3) executedOrder(his, lis, charge, pharmacy, pacs, nurse, p, CRP, 3, settleAmount);
            // 检查医嘱（已执行，PACS 有检查报告）
            if (i % 3 == 0) executedOrder(his, lis, charge, pharmacy, pacs, nurse, p, CT, 1, settleAmount);
            if (i % 4 == 1) executedOrder(his, lis, charge, pharmacy, pacs, nurse, p, DR, 1, settleAmount);
            if (i % 6 == 3) executedOrder(his, lis, charge, pharmacy, pacs, nurse, p, US, 2, settleAmount);
            // 药品医嘱（已执行 + 药房已发药）
            int drugCount = 2 + i % 3;
            for (int j = 0; j < drugCount; j++) {
                executedOrder(his, lis, charge, pharmacy, pacs, nurse, p, DRUGS[(i + j) % DRUGS.length], 1 + j % 3, settleAmount);
            }
            // 入院预交金
            prepay(charge, p, 2000 + (i % 5) * 1000, CHANNELS[i % 3], i);
            // 新患者的额外场景：升级后取消药品（药房已退药、费用已退费——药品线闭环不受LIS故障影响）
            if (i >= 12 && i % 8 == 5) {
                drugCanceledRefunded(his, charge, pharmacy, p, DRUGS[(i + 5) % DRUGS.length], addDays(p[6], 3), i);
            }
        }

        // ===== 预埋故障（与既有根因案例严格一致：5 名患者 / 5 笔 / ¥205） =====
        faultRecord(his, lis, charge, patients.get(0), CBC, "2026-08-21 08:30:00", "2026-08-21 10:05:00", settleAmount);
        faultRecord(his, lis, charge, patients.get(3), LIVER, "2026-08-22 09:00:00", "2026-08-22 11:20:00", settleAmount);
        faultRecord(his, lis, charge, patients.get(2), COAG, "2026-08-25 07:45:00", "2026-08-25 09:10:00", settleAmount);
        faultRecord(his, lis, charge, patients.get(4), URINE, "2026-08-28 10:15:00", "2026-08-28 13:40:00", settleAmount);
        faultRecord(his, lis, charge, patients.get(6), CBC, "2026-09-01 08:20:00", "2026-09-01 10:00:00", settleAmount);

        // ===== 对照组：升级前 X 撤销已正常退费 =====
        historicalRefunded(his, lis, charge, patients.get(10), CBC, "2026-08-06 08:00:00", "2026-08-06 09:30:00");
        historicalRefunded(his, lis, charge, patients.get(11), LIVER, "2026-08-02 08:30:00", "2026-08-02 10:00:00");

        // ===== 计费适配器状态映射表（故障点：缺 C） =====
        his.update("INSERT INTO status_map(src_system,src_status,src_status_name,target_action,updated_at) VALUES(?,?,?,?,?)", "LIS", "N", "已采样", "CHARGE", "2025-12-01 10:00:00");
        his.update("INSERT INTO status_map(src_system,src_status,src_status_name,target_action,updated_at) VALUES(?,?,?,?,?)", "LIS", "P", "已发布", "KEEP", "2025-12-01 10:00:00");
        his.update("INSERT INTO status_map(src_system,src_status,src_status_name,target_action,updated_at) VALUES(?,?,?,?,?)", "LIS", "X", "已撤销", "REFUND", "2025-12-01 10:00:00");

        // ===== LIS 状态字典（版本演进证据） =====
        lis.update("INSERT INTO lab_dict_status(status_code,status_name,app_version,effective_date,end_date) VALUES(?,?,?,?,?)", "N", "已采样", "v5.1", "2024-01-01", null);
        lis.update("INSERT INTO lab_dict_status(status_code,status_name,app_version,effective_date,end_date) VALUES(?,?,?,?,?)", "P", "已发布", "v5.1", "2024-01-01", null);
        lis.update("INSERT INTO lab_dict_status(status_code,status_name,app_version,effective_date,end_date) VALUES(?,?,?,?,?)", "X", "已撤销", "v5.1", "2024-01-01", "2026-08-15");
        lis.update("INSERT INTO lab_dict_status(status_code,status_name,app_version,effective_date,end_date) VALUES(?,?,?,?,?)", "C", "已撤销", "v5.2", "2026-08-15", null);

        // ===== 出院结算 + 补缴/退费 =====
        for (String[] p : patients) {
            if (!"出院".equals(p[8])) {
                continue;
            }
            BigDecimal total = settleAmount.getOrDefault(p[0], BigDecimal.ZERO);
            String settleId = "ST" + p[0].substring(2);
            charge.update("INSERT INTO settlement(settle_id,inhos_no,patient_name,total_amount,settle_time,settle_status) VALUES(?,?,?,?,?,?)",
                    settleId, p[0], p[1], total, p[7], "1");
            settlePay(charge, p, total, settleId);
        }

        log.info("住院演示数据生成完成：{} 名患者，{} 条医嘱，{} 笔费用，{} 条发药，{} 笔缴费",
                patients.size(), orderSeq, feeSeq, dispenseSeq, paySeq);

        // ===== 门诊业务线（同一框架平移：挂号→看诊→处方→缴费→执行） =====
        seedOpd(lis, charge, pharmacy, pacs, opd);

        // ===== 病案与临床语义底座数据（内涵质控预埋矛盾 + 危急值对照组） =====
        seedEmr(his, lis, charge, pharmacy, pacs, emr, nurse, patients, settleAmount);

        // ===== 处方审核数据底座（药品知识字典 + 医嘱剂量/频次 + 过敏史，SHACL Shape 1/2 的 ABox 输入） =====
        seedPrescriptionKnowledge(his, pharmacy, emr, patients);

        // ===== 双引擎互证案例（V13）：郑国庆药物相互作用 + 韩小梅儿童禁用（须在进销存/审核播种前，保证出库与审核覆盖新医嘱） =====
        seedDualEngineCases(his, lis, charge, pharmacy, pacs, nurse, patients, settleAmount);

        // ===== 业务扩展：药房进销存 + 处方审核闭环 + 发票/对账（V12） =====
        seedBusinessExpansion(his, charge, pharmacy);

        // ===== 业务域扩展：生命体征 + 麻醉记录 + DRG 入组（V14） =====
        seedDomainExpansion(nurse, emr, patients);

        // ===== 业务域再扩充（V15）：物资耗材 + 人事组织主数据 + 输血/院感/随访落表 =====
        seedMaterialOrgBlood(his, emr, patients);

        for (String ds : List.of("DS_HIS", "DS_LIS", "DS_CHARGE", "DS_PHARMACY", "DS_OPD", "DS_EMR", "DS_PACS", "DS_NURSE", "DS_MATERIAL")) {
            schemaScanService.scan(ds);
        }
    }

    /**
     * 公理结构化种子（V19，借鉴 Utopia）：幂等，每次启动都执行（先于业务数据新鲜度判断）。
     * 1) bm_axiom 中 axiom_type='互斥' 且主/宾语可解析为概念 code 的（如 AX-003 患者（男性）✕ 诊断（妊娠/妇科相关）），
     *    同步种入 bm_concept_disjoint（规范化为字典序单行，对称语义由服务层双向查询承担）；
     * 2) 关系公理示例：「属于」partOf 置传递；「工作于/包含成员」互设 inverse_of。
     */
    private void seedStructuredAxioms() {
        List<Map<String, Object>> mutexAxioms = platformJdbc.queryForList(
                "SELECT axiom_code, subject, object, description FROM bm_axiom WHERE axiom_type = '互斥'");
        for (Map<String, Object> ax : mutexAxioms) {
            String a = resolveConceptCode(String.valueOf(ax.get("subject")));
            String b = resolveConceptCode(String.valueOf(ax.get("object")));
            if (a == null || b == null || a.equals(b)) {
                continue; // 自由文本无法解析为概念对（如 AX-007 药品✕患者过敏原），保留文本公理兜底
            }
            String first = a.compareTo(b) < 0 ? a : b;
            String second = a.compareTo(b) < 0 ? b : a;
            Integer exists = platformJdbc.queryForObject(
                    "SELECT COUNT(*) FROM bm_concept_disjoint WHERE concept_a_code = ? AND concept_b_code = ?",
                    Integer.class, first, second);
            if (exists == null || exists == 0) {
                platformJdbc.update(
                        "INSERT INTO bm_concept_disjoint(concept_a_code, concept_b_code, definition, status) VALUES(?,?,?,'PUBLISHED')",
                        first, second, ax.get("axiom_code") + "：" + ax.get("description"));
                log.info("公理结构化：{} ✕ {} 已种入 bm_concept_disjoint（源自 {}）", first, second, ax.get("axiom_code"));
            }
        }

        // 传递公理示例：体征记录「属于」病案（partOf 沿组合链传递）
        Integer partOf = platformJdbc.queryForObject(
                "SELECT COUNT(*) FROM bm_relation WHERE from_concept='VITAL_SIGN' AND to_concept='EMR_RECORD' AND relation_name='属于'",
                Integer.class);
        if (partOf == null || partOf == 0) {
            platformJdbc.update(
                    "INSERT INTO bm_relation(from_concept, to_concept, relation_name, description, is_transitive) VALUES(?,?,?,?,1)",
                    "VITAL_SIGN", "EMR_RECORD", "属于", "partOf 公理示例：体征记录是病案的组成部分，「属于」沿组合链传递");
        } else {
            platformJdbc.update(
                    "UPDATE bm_relation SET is_transitive=1 WHERE from_concept='VITAL_SIGN' AND to_concept='EMR_RECORD' AND relation_name='属于'");
        }

        // 互逆公理示例：医务人员「工作于」科室 ⇄ 科室「包含成员」医务人员
        Integer inverse = platformJdbc.queryForObject(
                "SELECT COUNT(*) FROM bm_relation WHERE from_concept='DEPT' AND to_concept='STAFF' AND relation_name='包含成员'",
                Integer.class);
        if (inverse == null || inverse == 0) {
            platformJdbc.update(
                    "INSERT INTO bm_relation(from_concept, to_concept, relation_name, description, inverse_of) VALUES(?,?,?,?,'工作于')",
                    "DEPT", "STAFF", "包含成员", "inverse_of 公理示例：与「工作于」互为逆关系");
        }
        platformJdbc.update(
                "UPDATE bm_relation SET inverse_of='包含成员' WHERE from_concept='STAFF' AND to_concept='DEPT' AND relation_name='工作于'");
        platformJdbc.update(
                "UPDATE bm_relation SET inverse_of='工作于' WHERE from_concept='DEPT' AND to_concept='STAFF' AND relation_name='包含成员'");
    }

    /** 公理自由文本 → 概念 code：剥离（…）限定语后按 code 精确匹配，再按概念名称匹配；解析不出返回 null */
    private String resolveConceptCode(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.replaceAll("（.*?）", "").trim();
        List<Map<String, Object>> rows = platformJdbc.queryForList(
                "SELECT code FROM bm_concept WHERE code = ? OR name = ? LIMIT 1", cleaned, cleaned);
        return rows.isEmpty() ? null : String.valueOf(rows.get(0).get("code"));
    }

    /**
     * V15：物资库存/申领、科室/人员主数据（与现有医嘱、执行、审核里的姓名对齐）、
     * 输血（两台手术的备血与输注）、院感上报（术后切口感染）、出院随访。
     * V16：物资进销存闭环（期初入库→申领→发放出库→扣减库存），与药房进销存同模型，GOV-011 账实相符。
     */
    private void seedMaterialOrgBlood(JdbcTemplate his, JdbcTemplate emr, List<String[]> patients) {
        JdbcTemplate material = datasourceService.jdbc("DS_MATERIAL");

        // ---- 科室主数据 ----
        Object[][] depts = {
                {"D01", "心内科", "内一科病区", "临床", 45}, {"D02", "呼吸内科", "内一科病区", "临床", 40},
                {"D03", "消化内科", "内一科病区", "临床", 38}, {"D04", "普外科", "外二科病区", "临床", 42},
                {"D05", "骨科", "骨科病区", "临床", 36}, {"D06", "儿科", "内一科病区", "临床", 24},
                {"D07", "门诊部", null, "临床", 0}, {"D08", "检验科", null, "医技", 0},
                {"D09", "影像科", null, "医技", 0}, {"D10", "药房", null, "医技", 0},
                {"D11", "医务科", null, "职能", 0}, {"D12", "护理部", null, "职能", 0}};
        for (Object[] d : depts) {
            his.update("INSERT INTO dept(dept_code,dept_name,ward,category,bed_count) VALUES(?,?,?,?,?)", d);
        }

        // ---- 医务人员主数据（与医嘱/执行/审核/发药里的姓名一一对齐） ----
        Object[][] staffs = {
                {"S001", "王海涛", "医生", "主任医师", "D01"}, {"S002", "刘建军", "医生", "副主任医师", "D04"},
                {"S003", "赵文静", "医生", "主治医师", "D02"}, {"S004", "陈志远", "医生", "主治医师", "D03"},
                {"S005", "孙立军", "医生", "副主任医师", "D05"}, {"S006", "林晓东", "医生", "主治医师", "D01"},
                {"S007", "王芳", "护士", "主管护师", "D12"}, {"S008", "李晓", "护士", "护师", "D12"},
                {"S009", "张丽", "护士", "护师", "D12"}, {"S010", "王药师", "药师", "主管药师", "D10"},
                {"S011", "李药师", "药师", "药师", "D10"}, {"S012", "张药师", "药师", "药师", "D10"},
                {"S013", "沈安宁", "麻醉师", "副主任医师", "D04"}, {"S014", "钱卫平", "技师", "主管技师", "D08"},
                // 检验报告/检查执行/检查审核里的姓名同样挂到主数据（时间线下钻全员可解析）
                {"S015", "林芳", "技师", "主管检验师", "D08"}, {"S016", "何斌", "技师", "技师", "D09"},
                {"S017", "沈阅", "医生", "影像医师", "D09"}};
        for (Object[] s : staffs) {
            his.update("INSERT INTO staff(staff_id,staff_name,role,title,dept_code) VALUES(?,?,?,?,?)", s);
        }

        // ---- 物资耗材：库存 + 科室申领 ----
        Object[][] mats = {
                {"M001", "一次性输液器", "0.55mm", 1800, "支"}, {"M002", "一次性注射器", "5ml", 2200, "支"},
                {"M003", "静脉留置针", "24G", 640, "支"}, {"M004", "无菌敷贴", "6cm×7cm", 1500, "片"},
                {"M005", "可吸收缝合线", "3-0", 320, "包"}, {"M006", "医用棉球", "500g/包", 480, "包"}};
        for (Object[] m : mats) {
            material.update("INSERT INTO material_stock(material_code,material_name,spec,quantity,unit,warehouse,updated_at) VALUES(?,?,?,?,?,?,?)",
                    m[0], m[1], m[2], m[3], m[4], "中心库房", "2026-09-10 17:30:00");
        }
        Object[][] applies = {
                {"MA20260901001", "M001", "一次性输液器", 200, "心内科", "2026-09-01 08:30:00", "已发", "库管员 周正"},
                {"MA20260901002", "M003", "静脉留置针", 100, "骨科", "2026-09-01 09:10:00", "已发", "库管员 周正"},
                {"MA20260902003", "M002", "一次性注射器", 300, "普外科", "2026-09-02 08:45:00", "已发", "库管员 周正"},
                {"MA20260903004", "M004", "无菌敷贴", 150, "儿科", "2026-09-03 10:20:00", "已发", "库管员 周正"},
                {"MA20260905005", "M005", "可吸收缝合线", 40, "普外科", "2026-09-05 14:00:00", "已发", "库管员 周正"},
                {"MA20260908006", "M001", "一次性输液器", 150, "消化内科", "2026-09-08 08:50:00", "已发", "库管员 周正"},
                {"MA20260909007", "M006", "医用棉球", 60, "门诊部", "2026-09-09 11:30:00", "已发", "库管员 周正"},
                {"MA20260910008", "M002", "一次性注射器", 200, "呼吸内科", "2026-09-10 09:00:00", "待发", null},
                {"MA20260911009", "M003", "静脉留置针", 80, "心内科", "2026-09-11 08:40:00", "待发", null}};
        for (Object[] a : applies) {
            material.update("INSERT INTO material_apply(apply_id,material_code,material_name,quantity,apply_dept,apply_time,status,picker) VALUES(?,?,?,?,?,?,?,?)", a);
        }

        // ---- 进销存闭环（V16，与药房同模型）：期初入库 → 申领 → 发放出库 → 库存=Σ入-Σ出 ----
        Map<String, Integer> issued = new HashMap<>();
        int outSeq = 0;
        for (Object[] a : applies) {
            if (!"已发".equals(a[6])) {
                continue;   // 待发申领不产生出库，库存暂不扣减
            }
            String code = (String) a[1];
            int qty = (Integer) a[3];
            issued.merge(code, qty, Integer::sum);
            LocalDateTime outTime = LocalDateTime.parse((String) a[5], FMT).plusHours(2);
            material.update("INSERT INTO material_out(out_id,apply_id,material_code,material_name,quantity,out_dept,out_time,picker) VALUES(?,?,?,?,?,?,?,?)",
                    String.format("MO2026%07d", ++outSeq), a[0], code, a[2], qty, a[4], outTime.format(FMT), "库管员 周正");
        }
        int inSeq = 0;
        for (Object[] m : mats) {
            int opening = (Integer) m[3] + issued.getOrDefault((String) m[0], 0);
            material.update("INSERT INTO material_in(in_id,material_code,material_name,quantity,in_time,operator) VALUES(?,?,?,?,?,?)",
                    String.format("MI2026%07d", ++inSeq), m[0], m[1], opening, "2026-08-01 08:30:00", "库管员 周正");
        }

        // ---- 输血：两台手术的备血→配血→输注（郑国庆阑尾切除术 / 王强胸腔闭式引流术） ----
        his.update("INSERT INTO blood_apply(apply_id,inhos_no,patient_name,blood_type,component,quantity,apply_time,status) VALUES(?,?,?,?,?,?,?,?)",
                "BA20260804001", "ZY20260802011", "郑国庆", "B型 Rh+", "去白细胞红细胞", 2.0, "2026-08-04 08:20:00", "已发血");
        his.update("INSERT INTO blood_apply(apply_id,inhos_no,patient_name,blood_type,component,quantity,apply_time,status) VALUES(?,?,?,?,?,?,?,?)",
                "BA20260828001", "ZY20260816003", "王强", "A型 Rh+", "去白细胞红细胞", 2.0, "2026-08-28 08:50:00", "已发血");
        his.update("INSERT INTO blood_transfusion(trans_id,apply_id,inhos_no,blood_no,trans_time,nurse,reaction) VALUES(?,?,?,?,?,?,?)",
                "BT20260804001", "BA20260804001", "ZY20260802011", "BG3301872", "2026-08-04 10:40:00", "护士 王芳", "无");
        his.update("INSERT INTO blood_transfusion(trans_id,apply_id,inhos_no,blood_no,trans_time,nurse,reaction) VALUES(?,?,?,?,?,?,?)",
                "BT20260828001", "BA20260828001", "ZY20260816003", "BG3301905", "2026-08-28 10:50:00", "护士 李晓", "无");

        // ---- 院感上报：术后切口感染监测 ----
        emr.update("INSERT INTO infection_report(report_id,inhos_no,patient_name,infection_type,pathogen,report_time,status) VALUES(?,?,?,?,?,?,?)",
                "IR20260806001", "ZY20260802011", "郑国庆", "切口感染", "金黄色葡萄球菌", "2026-08-06 15:00:00", "已结案");
        emr.update("INSERT INTO infection_report(report_id,inhos_no,patient_name,infection_type,pathogen,report_time,status) VALUES(?,?,?,?,?,?,?)",
                "IR20260804001", "ZY20260728012", "冯雪", "呼吸道感染", "肺炎克雷伯菌", "2026-08-04 11:00:00", "已核实");

        // ---- 出院随访：5 名出院患者，2 已完成 3 待随访 ----
        Object[][] fus = {
                {"FU20260905001", "ZY20260815001", "张建国", "出院随访", "2026-09-05 10:00:00", "2026-09-05 10:15:00", "已完成", "电话随访：恢复良好，遵医嘱服药，费用疑问已解答"},
                {"FU20260901002", "ZY20260812002", "李红梅", "慢病随访", "2026-09-01 14:00:00", "2026-09-01 14:12:00", "已完成", "糖尿病随访：空腹血糖 6.8，继续当前方案"},
                {"FU20260912003", "ZY20260816003", "王强", "出院随访", "2026-09-12 09:30:00", null, "待随访", "术后复查提醒"},
                {"FU20260912004", "ZY20260805006", "陈芳", "慢病随访", "2026-09-12 15:00:00", null, "待随访", "高血压用药依从性随访"},
                {"FU20260913005", "ZY20260728012", "冯雪", "出院随访", "2026-09-13 10:00:00", null, "待随访", "肺部感染愈后随访"}};
        for (Object[] f : fus) {
            emr.update("INSERT INTO followup(fu_id,inhos_no,patient_name,fu_type,plan_time,done_time,status,content) VALUES(?,?,?,?,?,?,?,?)", f);
        }
    }

    /**
     * V14 业务域扩展种子：每名住院患者 3 次生命体征（体温单），
     * 每台手术一条麻醉记录（麻醉单），每份病案一条 DRG 入组（医保支付口径）。
     */
    private void seedDomainExpansion(JdbcTemplate nurse, JdbcTemplate emr, List<String[]> patients) {
        // ---- 生命体征：入院日/次日/第三日 9 点各测一次（正常值带小幅波动） ----
        String[] vsNurses = {"护士 王芳", "护士 李晓", "护士 张丽"};
        int vsSeq = 0;
        for (int i = 0; i < patients.size(); i++) {
            String[] p = patients.get(i);
            LocalDateTime admit = LocalDateTime.parse(p[6], FMT);
            for (int d = 0; d < 3; d++) {
                LocalDateTime t = admit.plusDays(d).withHour(9).withMinute(0);
                double temp = 36.2 + ((i + d) % 7) * 0.2;
                int pulse = 66 + ((i * 3 + d) % 5) * 8;
                int sys = 112 + ((i + d * 2) % 6) * 8;
                int dia = 72 + ((i + d) % 4) * 6;
                nurse.update("INSERT INTO vital_sign(vs_id,inhos_no,patient_name,record_time,temperature,pulse,bp,nurse) VALUES(?,?,?,?,?,?,?,?)",
                        String.format("VS%08d", ++vsSeq), p[0], p[1], t.format(FMT),
                        Math.round(temp * 10) / 10.0, pulse, sys + "/" + dia, vsNurses[(i + d) % 3]);
            }
        }

        // ---- 麻醉记录：每台手术一条（全麻/硬膜外，术前 15 分钟开始） ----
        String[] methods = {"全身麻醉", "硬膜外麻醉"};
        List<Map<String, Object>> surgeries = emr.queryForList("SELECT * FROM emr_surgery");
        int anesSeq = 0;
        for (Map<String, Object> s : surgeries) {
            LocalDateTime proc = LocalDateTime.parse(addMinutes(String.valueOf(s.get("proc_time")), 0), FMT);
            emr.update("INSERT INTO anesthesia_record(anes_id,surg_id,inhos_no,anesthetist,method,start_time,end_time) VALUES(?,?,?,?,?,?,?)",
                    String.format("AN%06d", ++anesSeq), s.get("surg_id"), s.get("inhos_no"),
                    "麻醉医师 沈安宁", methods[(anesSeq - 1) % 2],
                    proc.minusMinutes(15).format(FMT), proc.plusHours(1).format(FMT));
        }

        // ---- DRG 入组：每份病案一条（按主诊断映射病组，医保打包付费口径） ----
        Map<String, String[]> drgMap = Map.ofEntries(
                Map.entry("冠心病", new String[]{"FM23", "冠状动脉疾患伴并发症", "1.215"}),
                Map.entry("2型糖尿病", new String[]{"KS13", "糖尿病伴并发症", "0.986"}),
                Map.entry("胸腔积液", new String[]{"ET21", "胸膜疾患伴手术操作", "1.542"}),
                Map.entry("急性胃肠炎", new String[]{"GK23", "胃肠炎无并发症", "0.462"}),
                Map.entry("胫腓骨骨折", new String[]{"IB25", "下肢骨折伴手术", "1.873"}),
                Map.entry("高血压3级", new String[]{"FM41", "高血压无并发症", "0.418"}),
                Map.entry("腹股沟疝", new String[]{"GE13", "腹股沟疝伴手术", "1.124"}),
                Map.entry("股骨颈骨折", new String[]{"IB21", "髋部骨折伴手术", "2.305"}),
                Map.entry("高血压2级", new String[]{"FM41", "高血压无并发症", "0.418"}),
                Map.entry("慢性胃炎", new String[]{"GK25", "胃炎无并发症", "0.385"}),
                Map.entry("阑尾炎", new String[]{"GD25", "阑尾切除术", "1.036"}),
                Map.entry("肺部感染", new String[]{"ES33", "呼吸系统感染", "0.927"}),
                Map.entry("卵巢囊肿", new String[]{"NB21", "卵巢良性病变", "0.764"}));
        List<Map<String, Object>> records = emr.queryForList("SELECT record_id, inhos_no, diag_main, create_time FROM emr_record");
        int drgSeq = 0;
        for (Map<String, Object> r : records) {
            String[] drg = drgMap.getOrDefault(String.valueOf(r.get("diag_main")),
                    new String[]{"ZZ99", "其他病组", "0.500"});
            emr.update("INSERT INTO drg_group(drg_id,record_id,inhos_no,drg_code,drg_name,weight,group_time) VALUES(?,?,?,?,?,?,?)",
                    String.format("DRG%05d", ++drgSeq), r.get("record_id"), r.get("inhos_no"),
                    drg[0], drg[1], new BigDecimal(drg[2]), r.get("create_time"));
        }
    }

    /**
     * 双引擎互证案例（平台规则 × SHACL Shape 同源命中）：
     * 郑国庆：轮转医嘱已有 D002 布洛芬，加开 D012 华法林 → 相互作用（公理AX-009 / SHACL Shape 5）；
     * 韩小梅（8 岁）：布洛芬缓释胶囊（缓释剂型 12 岁以下不宜）→ 儿童禁用（SHACL Shape 3）。
     */
    private void seedDualEngineCases(JdbcTemplate his, JdbcTemplate lis, JdbcTemplate charge,
                                     JdbcTemplate pharmacy, JdbcTemplate pacs, JdbcTemplate nurse,
                                     List<String[]> patients, Map<String, BigDecimal> settleAmount) {
        executedOrder(his, lis, charge, pharmacy, pacs, nurse, patients.get(10), WARFARIN, 2, settleAmount); // 郑国庆-华法林
        executedOrder(his, lis, charge, pharmacy, pacs, nurse, patients.get(40), DRUGS[1], 3, settleAmount); // 韩小梅-布洛芬
    }

    /**
     * 进销存口径：库存 = Σ入库 - Σ出库（发药出库按发药记录逐药汇总），保证 GOV-007 账实相符。
     * 处方审核：全部已执行药品医嘱"通过"，唯冯雪超量布洛芬医嘱"驳回"——与 SHACL Shape 2 同一违规的平台内证据。
     */
    private void seedBusinessExpansion(JdbcTemplate his, JdbcTemplate charge, JdbcTemplate pharmacy) {
        // ---- 进销存：每药一单采购（1000盒）→ 入库；按发药记录汇总出库；库存=入-出 ----
        // 覆盖全部药品（含 D012 华法林，不在轮转数组中）
        List<Item> allDrugs = new ArrayList<>(Arrays.asList(DRUGS));
        allDrugs.add(WARFARIN);
        String[] suppliers = {"国药控股", "华润医药", "九州通医药"};
        for (int i = 0; i < allDrugs.size(); i++) {
            Item d = allDrugs.get(i);
            String poId = "PO20260801" + d.code();
            pharmacy.update("INSERT INTO purchase_order(po_id,drug_code,drug_name,quantity,unit_price,supplier,status,create_time) VALUES(?,?,?,?,?,?,?,?)",
                    poId, d.code(), d.name(), 1000, d.price(), suppliers[i % 3], "已入库", "2026-08-01 09:00:00");
            pharmacy.update("INSERT INTO stock_in(in_id,po_id,drug_code,drug_name,quantity,in_time,operator) VALUES(?,?,?,?,?,?,?)",
                    "IN" + poId.substring(2), poId, d.code(), d.name(), 1000, "2026-08-02 10:00:00", "库管员 周正");
        }
        List<Map<String, Object>> outStats = pharmacy.queryForList(
                "SELECT item_code, item_name, COUNT(*) cnt FROM dispense_record WHERE status='1' GROUP BY item_code, item_name");
        for (Map<String, Object> s : outStats) {
            int outQty = ((Number) s.get("cnt")).intValue();
            pharmacy.update("INSERT INTO stock_out(out_id,drug_code,drug_name,quantity,out_type,ref_id,out_time,operator) VALUES(?,?,?,?,?,?,?,?)",
                    "OUT20260910" + s.get("item_code"), s.get("item_code"), s.get("item_name"), outQty,
                    "发药出库", "BATCH-20260910", "2026-09-10 17:00:00", "库管员 周正");
            pharmacy.update("INSERT INTO drug_stock(drug_code,drug_name,quantity,unit,warehouse,updated_at) VALUES(?,?,?,?,?,?)",
                    s.get("item_code"), s.get("item_name"), 1000 - outQty, "盒", "住院药房", "2026-09-10 17:05:00");
        }

        // ---- 处方审核：全部"通过"，唯冯雪 D002 超量医嘱"驳回"（Shape 2 同一违规的平台证据） ----
        List<Map<String, Object>> drugOrders = his.queryForList(
                "SELECT order_id, inhos_no, item_code, create_time FROM medical_order WHERE order_type='药品' AND order_status='1'");
        int reviewSeq = 0;
        for (Map<String, Object> o : drugOrders) {
            boolean overdose = "ZY20260728012".equals(String.valueOf(o.get("inhos_no")))
                    && "D002".equals(String.valueOf(o.get("item_code")));
            String reviewTime = addMinutes(String.valueOf(o.get("create_time")), 30);
            pharmacy.update("INSERT INTO presc_review(review_id,order_id,inhos_no,pharmacist,review_result,reject_reason,review_time) VALUES(?,?,?,?,?,?,?)",
                    String.format("PR%08d", ++reviewSeq), o.get("order_id"), o.get("inhos_no"),
                    PHARMACISTS[reviewSeq % PHARMACISTS.length],
                    overdose ? "驳回" : "通过",
                    overdose ? "单日剂量 3.0g 超日最大剂量 2.4g，请医生调整剂量后重新提交" : null,
                    reviewTime);
        }

        // ---- 结算管理：每张结算单一已开发票；近 5 日 × 3 渠道对账全"平" ----
        List<Map<String, Object>> settlements = charge.queryForList("SELECT * FROM settlement");
        for (Map<String, Object> st : settlements) {
            String sid = String.valueOf(st.get("settle_id"));
            charge.update("INSERT INTO invoice(invoice_id,settle_id,inhos_no,patient_name,amount,invoice_time,status) VALUES(?,?,?,?,?,?,?)",
                    "INV" + sid.substring(2), sid, st.get("inhos_no"), st.get("patient_name"),
                    st.get("total_amount"), st.get("settle_time"), "已开");
        }
        Object[][] recs = {
                {"2026-09-06", "现金", 8620.50}, {"2026-09-06", "扫码", 15230.00}, {"2026-09-06", "医保", 42100.00},
                {"2026-09-07", "现金", 7480.00}, {"2026-09-07", "扫码", 18645.50}, {"2026-09-07", "医保", 38950.00},
                {"2026-09-08", "现金", 9120.00}, {"2026-09-08", "扫码", 16780.50}, {"2026-09-08", "医保", 45230.00},
                {"2026-09-09", "现金", 6890.50}, {"2026-09-09", "扫码", 19520.00}, {"2026-09-09", "医保", 40680.00},
                {"2026-09-10", "现金", 8260.00}, {"2026-09-10", "扫码", 17890.50}, {"2026-09-10", "医保", 43540.00}};
        int recSeq = 0;
        for (Object[] r : recs) {
            charge.update("INSERT INTO reconcile_record(rec_id,rec_date,channel,system_amount,channel_amount,diff,status,remark) VALUES(?,?,?,?,?,?,?,?)",
                    String.format("RC%06d", ++recSeq), r[0], r[1], r[2], r[2], 0, "平", null);
        }
    }

    private String addMinutes(String dateTime, int minutes) {
        // 宽容解析：JDBC 可能返回 "2026-07-28 10:00:00"（Timestamp）或 "2026-07-28T10:00"（LocalDateTime）
        String s = dateTime.replace('T', ' ');
        if (s.length() > 19) {
            s = s.substring(0, 19);
        }
        if (s.length() == 16) {
            s = s + ":00";
        }
        return java.time.LocalDateTime.parse(s, FMT).plusMinutes(minutes).format(FMT);
    }

    /** 药品知识字典 + 全量医嘱标准剂量/频次 + 预埋两个违规：陈芳头孢过敏仍开头孢（Shape 1）、冯雪布洛芬超日最大剂量（Shape 2）。 */
    private void seedPrescriptionKnowledge(JdbcTemplate his, JdbcTemplate pharmacy, JdbcTemplate emr, List<String[]> patients) {
        // 药品知识字典（drug_code, 名称, 日最大剂量, 单位, 过敏原, 儿童禁用, 相互作用）——D003 无日上限，验证 Shape 不误报
        // D002 布洛芬缓释（缓释剂型 12 岁以下不宜）↔ D012 华法林（联用增加出血风险）：相互作用对称登记
        Object[][] dict = {
                {"D001", "阿莫西林胶囊", 4.0, "g", "青霉素", "N", null},
                {"D002", "布洛芬缓释胶囊", 2.4, "g", null, "Y", "D012"},
                {"D003", "0.9%氯化钠注射液", null, "ml", null, "N", null},
                {"D004", "胰岛素注射液", 60.0, "单位", null, "N", null},
                {"D005", "维生素C片", 2.0, "g", null, "N", null},
                {"D006", "头孢克肟分散片", 0.4, "g", "头孢", "N", null},
                {"D007", "奥美拉唑肠溶胶囊", 0.08, "g", null, "N", null},
                {"D008", "氨溴索口服液", 0.12, "g", null, "N", null},
                {"D009", "硝苯地平缓释片", 0.06, "g", null, "N", null},
                {"D010", "盐酸二甲双胍片", 2.55, "g", null, "N", null},
                {"D011", "头孢呋辛酯片", 1.0, "g", "头孢", "N", null},
                {"D012", "华法林钠片", 0.01, "g", null, "N", "D002"}};
        for (Object[] d : dict) {
            pharmacy.update("INSERT INTO drug_dict(drug_code,drug_name,max_daily_dose,dose_unit,allergen,child_forbidden,interacts_with) VALUES(?,?,?,?,?,?,?)", d);
        }

        // 全量药品医嘱按说明书给标准剂量/频次（换算日剂量均 ≤ 日最大剂量，保证默认干净）
        Object[][] std = {
                {"D001", 0.5, "g", "tid"}, {"D002", 0.3, "g", "bid"}, {"D003", 250.0, "ml", "qd"},
                {"D004", 8.0, "单位", "tid"}, {"D005", 0.2, "g", "tid"}, {"D006", 0.1, "g", "bid"},
                {"D007", 0.02, "g", "qd"}, {"D008", 0.03, "g", "tid"}, {"D009", 0.03, "g", "qd"},
                {"D010", 0.5, "g", "tid"}, {"D011", 0.25, "g", "bid"}, {"D012", 0.005, "g", "qd"}};
        for (Object[] s : std) {
            his.update("UPDATE medical_order SET single_dose=?, dose_unit=?, frequency=? WHERE item_code=?",
                    s[1], s[2], s[3], s[0]);
        }

        // 违规一（过敏禁忌）：陈芳对头孢严重过敏，循环医嘱里恰有 D006 头孢克肟 → SHACL Shape 1 / 平台互斥公理 AX 层面命中
        emr.update("INSERT INTO patient_allergy(inhos_no,allergen,severity) VALUES(?,?,?)",
                "ZY20260805006", "头孢", "严重");

        // 违规二（剂量上限）：冯雪布洛芬 1.0g tid = 3.0g/日 > 日最大 2.4g → SHACL Shape 2 命中
        his.update("UPDATE medical_order SET single_dose=1.0, dose_unit='g', frequency='tid' WHERE inhos_no=? AND item_code='D002'",
                patients.get(11)[0]);
    }

    private int opdRegSeq = 0;
    private int opdVisitSeq = 0;
    private int prescSeq = 0;

    /** 门诊演示数据：25 名虚构门诊患者，处方复用医嘱闭环（检验→LIS、药品→药房、检查→PACS、缴费→收费系统） */
    private void seedOpd(JdbcTemplate lis, JdbcTemplate charge, JdbcTemplate pharmacy, JdbcTemplate pacs, JdbcTemplate opd) {
        String[][] names = {
                {"冯建华", "男", "52"}, {"蒋雪梅", "女", "38"}, {"沈国强", "男", "45"}, {"韩丽萍", "女", "29"},
                {"曹志明", "男", "60"}, {"谢桂芳", "女", "66"}, {"邓文杰", "男", "34"}, {"许玉兰", "女", "48"},
                {"傅伟东", "男", "41"}, {"邹雅琴", "女", "55"}, {"熊志刚", "男", "37"}, {"孟丽华", "女", "43"},
                {"秦国栋", "男", "58"}, {"尤晓燕", "女", "26"}, {"鲁明轩", "男", "49"}, {"章素云", "女", "63"},
                {"窦建军", "男", "35"}, {"翁丽娟", "女", "51"}, {"甄志远", "男", "44"}, {"储秋月", "女", "32"},
                {"柯振海", "男", "56"}, {"屠雅静", "女", "40"}, {"樊国梁", "男", "62"}, {"霍晓彤", "女", "27"},
                {"聂文斌", "男", "47"}};
        String[] diags = {"上呼吸道感染", "急性胃肠炎", "高血压复诊", "2型糖尿病复诊", "腰椎间盘突出", "荨麻疹", "慢性胃炎", "支气管炎"};
        String[] depts = {"呼吸内科", "消化内科", "心内科", "内分泌科", "骨科", "皮肤科"};
        String[] doctors = {"王海涛", "刘建军", "赵文静", "陈志远", "孙立军", "林晓东"};

        for (int i = 0; i < names.length; i++) {
            String cardNo = String.format("KC2026%06d", 900001 + i);
            LocalDateTime regTime = LocalDateTime.of(2026, 8, 20, 8, 0).plusDays(i % 20).plusHours(i % 8).plusMinutes(i * 7 % 50);
            String dept = depts[i % depts.length];
            String doctor = doctors[i % doctors.length];
            boolean refunded = i == 7; // 一名退号患者
            // 挂号
            opd.update("INSERT INTO opd_reg(reg_id,pat_card_no,pat_name,reg_dept,reg_doctor,reg_fee,reg_status,reg_time) VALUES(?,?,?,?,?,?,?,?)",
                    "RG" + regTime.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + String.format("%04d", ++opdRegSeq),
                    cardNo, names[i][0], dept, doctor, i % 6 == 0 ? 25.00 : 15.00,
                    refunded ? "2" : "1", regTime.format(FMT));
            if (refunded) {
                continue; // 退号：无后续看诊
            }
            // 看诊 + 诊断
            String visitId = "OV" + regTime.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + String.format("%04d", ++opdVisitSeq);
            LocalDateTime visitTime = regTime.plusMinutes(30);
            opd.update("INSERT INTO opd_visit(visit_id,card_no,diag,visit_time,doctor,status) VALUES(?,?,?,?,?,?)",
                    visitId, cardNo, diags[i % diags.length], visitTime.format(FMT), doctor, "2");

            // 处方：2-4 项（药品/检验/检查混合）
            int itemCount = 2 + i % 3;
            for (int j = 0; j < itemCount; j++) {
                Item item;
                if (j == 0 && i % 3 == 0) {
                    item = LABS_FOR_OPD[i % LABS_FOR_OPD.length];
                } else if (j == 1 && i % 4 == 1) {
                    item = DR;
                } else {
                    item = DRUGS[(i * 3 + j) % DRUGS.length];
                }
                int qty = "DRUG".equals(item.type()) ? 1 + (i + j) % 3 : 1;
                double lineAmount = item.price() * qty;
                boolean unpaid = (i % 11 == 10 && j == itemCount - 1); // 偶发未缴费处方
                boolean voided = (i == 13 && j == 0);                  // 一张作废处方（已退费）
                String status = voided ? "0" : (unpaid ? "1" : "3");
                String prescId = "PR" + visitTime.format(DateTimeFormatter.ofPattern("yyyyMMdd")) + String.format("%04d", ++prescSeq);
                LocalDateTime prescTime = visitTime.plusMinutes(10 + j * 5L);
                opd.update("INSERT INTO opd_presc(presc_id,visit_id,card_no,item_code,item_name,item_type,quantity,price,presc_status,create_time) VALUES(?,?,?,?,?,?,?,?,?,?)",
                        prescId, visitId, cardNo, item.code(), item.name(),
                        typeName(item), qty, lineAmount, status, prescTime.format(FMT));

                if (!voided && !unpaid) {
                    // 执行环节：检验→LIS（申请+报告），药品→药房发药，检查→PACS报告
                    if ("LAB".equals(item.type())) {
                        String applyId = nextId("LA", prescTime.format(FMT), ++applySeq);
                        lis.update("INSERT INTO lab_apply(apply_id,order_id,patient_no,patient_name,item_code,item_name,apply_status,apply_time,update_time) VALUES(?,?,?,?,?,?,?,?,?)",
                                applyId, prescId, cardNo, names[i][0], item.code(), item.name(), "P",
                                prescTime.format(FMT), prescTime.plusHours(3).format(FMT));
                        lis.update("INSERT INTO lab_report(report_id,apply_id,patient_no,item_code,result_status,report_time,reporter) VALUES(?,?,?,?,?,?,?)",
                                nextId("LR", prescTime.format(FMT), ++reportSeq), applyId, cardNo, item.code(), "N",
                                prescTime.plusHours(3).format(FMT), "检验师 林芳");
                    } else if ("DRUG".equals(item.type())) {
                        pharmacy.update("INSERT INTO dispense_record(dispense_id,order_id,patient_no,item_code,item_name,status,dispense_time,pharmacist) VALUES(?,?,?,?,?,?,?,?)",
                                nextId("DP", prescTime.format(FMT), ++dispenseSeq), prescId, cardNo, item.code(), item.name(), "1",
                                prescTime.plusHours(1).format(FMT), PHARMACISTS[(int) (dispenseSeq % 3)]);
                    } else {
                        pacs.update("INSERT INTO exam_report(exam_id,order_id,patient_no,patient_name,item_code,item_name,abnormal_flag,conclusion,exam_time,report_time,technician,reviewer) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                                nextId("EX", prescTime.format(FMT), ++examSeq), prescId, cardNo, names[i][0], item.code(), item.name(),
                                "N", normalConclusion(item.code()), prescTime.plusHours(2).format(FMT),
                                prescTime.plusHours(4).format(FMT), "技师 何斌", "影像医师 沈阅");
                    }
                }
                if (voided) {
                    charge.update("INSERT INTO pay_record(pay_id,inhos_no,amount,pay_type,channel,pay_time,operator) VALUES(?,?,?,?,?,?,?)",
                            nextId("PAY", prescTime.format(FMT), ++paySeq), cardNo, lineAmount, "4", "扫码",
                            prescTime.plusMinutes(20).format(FMT), "收费处 小周");
                    charge.update("INSERT INTO pay_record(pay_id,inhos_no,amount,pay_type,channel,pay_time,operator) VALUES(?,?,?,?,?,?,?)",
                            nextId("PAY", prescTime.format(FMT), ++paySeq), cardNo, lineAmount, "3", "扫码",
                            prescTime.plusHours(1).format(FMT), "收费处 小周");
                }
            }
            // 门诊缴费（按已执行处方汇总）
            List<Map<String, Object>> payables = opd.queryForList(
                    "SELECT IFNULL(SUM(price),0) AS amt FROM opd_presc WHERE card_no = ? AND presc_status = '3'", cardNo);
            double payAmount = ((BigDecimal) payables.get(0).get("amt")).doubleValue();
            if (payAmount > 0) {
                charge.update("INSERT INTO pay_record(pay_id,inhos_no,amount,pay_type,channel,pay_time,operator) VALUES(?,?,?,?,?,?,?)",
                        nextId("PAY", visitTime.format(FMT), ++paySeq), cardNo, payAmount, "4", CHANNELS[i % 3],
                        visitTime.plusMinutes(25).format(FMT), "收费处 小周");
            }
        }
        log.info("门诊演示数据生成完成：{} 名门诊患者，{} 张处方", names.length, prescSeq);
    }

    private static final Item[] LABS_FOR_OPD = {CBC, CRP, ELECTROLYTE, LIVER};

    // ==================== 病案与临床语义底座 ====================

    /**
     * 病案数据：12 份病案，5 份预埋逻辑矛盾（每份对应一条质控规则），其余为干净对照。
     * 危急值对照：杨光 C反应蛋白异常且无处置（→危重预警）；冯雪异常但已有感染诊断+抗生素（→不预警）。
     */
    private void seedEmr(JdbcTemplate his, JdbcTemplate lis, JdbcTemplate charge, JdbcTemplate pharmacy,
                         JdbcTemplate pacs, JdbcTemplate emr, JdbcTemplate nurse, List<String[]> patients,
                         Map<String, BigDecimal> settleAmount) {
        // 诊断字典（对接 SNOMED CT / ICD-10，示例编码）
        Object[][] dict = {
                {"DG001", "高血压", "38341003", "I10"}, {"DG002", "2型糖尿病", "44054006", "E11"},
                {"DG003", "肺部感染", "233604007", "J98.4"}, {"DG004", "急性胃肠炎", "40956001", "A09"},
                {"DG005", "上呼吸道感染", "54150009", "J06.9"}, {"DG006", "冠心病", "53741008", "I25.1"},
                {"DG007", "心律失常", "698247007", "I49.9"}, {"DG008", "胫腓骨骨折", "31921005", "S82.3"},
                {"DG009", "胆囊结石伴胆囊炎", "76581006", "K80.1"}, {"DG010", "卵巢囊肿", "79873004", "N83.2"},
                {"DG011", "胸腔积液", "60046008", "J94.8"}, {"DG012", "腹股沟疝", "396232005", "K40.9"},
                {"DG013", "慢性胃炎", "25374005", "K29.5"}, {"DG014", "上消化道出血", "37372002", "K92.2"},
                {"DG015", "阑尾炎", "66754008", "K35.9"}};
        for (Object[] d : dict) {
            emr.update("INSERT INTO diag_dict(diag_code,diag_name,snomed_code,icd10) VALUES(?,?,?,?)", d);
        }

        // 干净病例所需的补充处置医嘱（让"通过"经得起逐条核验）
        executedOrder(his, lis, charge, pharmacy, pacs, nurse, patients.get(5), ANTIHYPERTENSIVE, 2, settleAmount); // 陈芳-硝苯地平
        executedOrder(his, lis, charge, pharmacy, pacs, nurse, patients.get(11), ANTIBIOTIC, 3, settleAmount);      // 冯雪-头孢呋辛
        // 危急值：两份异常的 C反应蛋白报告
        executedLabWithResult(his, lis, charge, patients.get(11), CRP, 4, "A", settleAmount);          // 冯雪（已处置）
        executedLabWithResult(his, lis, charge, patients.get(6), CRP, 2, "A", settleAmount);           // 杨光（未处置→预警）
        // PACS 异常对照：王强 CT 示胸腔积液（有手术处置→不违规）；杨光 CT 示肺结节（无处置→RULE-QC-006）
        executedOrderWithExam(his, lis, charge, pharmacy, pacs, nurse, patients.get(2), CT, 3, settleAmount,
                "Y", "右侧胸腔积液，胸腔闭式引流术后改变。");
        pacs.update("UPDATE exam_report SET abnormal_flag='Y', conclusion=? WHERE patient_no=? AND item_code='R001'",
                "右肺上叶结节影（约8mm），建议进一步检查。", "ZY20260822007");

        // 病案：{患者idx, 病案类型, 主要诊断, 全部诊断(逗号分隔,含并发症), 摘要}
        Object[][] records = {
                {0, "出院小结", "冠心病", "冠心病，心律失常", "患者因胸闷气短入院，予扩冠、调律治疗后好转出院。"},
                {1, "出院小结", "2型糖尿病", "2型糖尿病", "患者血糖偏高入院调整，出院随诊。（注：无血糖检验记录——质控点）"},
                {2, "出院小结", "胸腔积液", "胸腔积液", "患者胸腔积液，于2026-08-28行胸腔闭式引流术，术后恢复良好。"},
                {3, "出院小结", "急性胃肠炎", "急性胃肠炎", "患者腹痛腹泻入院，补液对症治疗后好转。"},
                {4, "入院记录", "胫腓骨骨折", "胫腓骨骨折", "外伤致右小腿畸形疼痛，拟行内固定术。"},
                {5, "出院小结", "高血压3级", "高血压3级", "患者血压升高10年，本次调整降压方案（硝苯地平缓释片）后平稳出院。"},
                {6, "入院记录", "腹股沟疝", "腹股沟疝", "腹股沟可复性包块入院，拟择期手术。（注：C反应蛋白异常未处置——质控点）"},
                {7, "出院小结", "股骨颈骨折", "股骨颈骨折", "跌倒致髋部疼痛，行闭合复位内固定。"},
                {8, "入院记录", "高血压2级", "高血压2级", "血压升高伴头晕入院，予硝苯地平控制。"},
                {9, "入院记录", "慢性胃炎", "慢性胃炎，上消化道出血", "上腹隐痛伴黑便。（注：并发症未予质子泵抑制剂——质控点）"},
                {10, "出院小结", "阑尾炎", "阑尾炎", "转移性右下腹痛，于2026-08-04行阑尾切除术。（注：术前缺凝血四项——质控点）"},
                {11, "出院小结", "肺部感染", "肺部感染", "咳嗽发热，C反应蛋白升高，予头孢呋辛抗感染后好转出院（危急值已处置）。"},
                {12, "入院记录", "卵巢囊肿", "卵巢囊肿", "男性患者，诊断卵巢囊肿。（注：性别诊断互斥违反公理AX-003——质控点）"}};
        int emrSeq = 0;
        for (Object[] r : records) {
            String[] p = patients.get((Integer) r[0]);
            String recordId = "EMR" + p[6].substring(0, 10).replace("-", "") + String.format("%03d", ++emrSeq);
            emr.update("INSERT INTO emr_record(record_id,inhos_no,patient_name,record_type,diag_main,diag_list,content,doctor,create_time,qc_status) VALUES(?,?,?,?,?,?,?,?,?,?)",
                    recordId, p[0], p[1], r[1], r[2], r[3], r[4], p[9], addDays(p[6], 2), "未质控");
        }

        // 手术记录
        emr.update("INSERT INTO emr_surgery(surg_id,inhos_no,proc_name,proc_time,surgeon,proc_level) VALUES(?,?,?,?,?,?)",
                "SG20260804001", "ZY20260802011", "阑尾切除术", "2026-08-04 10:00:00", "刘建军", "二级");
        emr.update("INSERT INTO emr_surgery(surg_id,inhos_no,proc_name,proc_time,surgeon,proc_level) VALUES(?,?,?,?,?,?)",
                "SG20260828002", "ZY20260816003", "胸腔闭式引流术", "2026-08-28 10:00:00", "赵文静", "二级");

        log.info("病案数据生成完成：{} 份病案 / 2 台手术 / 15 条诊断字典（SNOMED CT+ICD-10 对接示例）", records.length);
    }

    /** 指定结果状态的检验医嘱（用于危急值：A=异常） */
    private void executedLabWithResult(JdbcTemplate his, JdbcTemplate lis, JdbcTemplate charge,
                                       String[] patient, Item item, int dayOffset, String resultStatus,
                                       Map<String, BigDecimal> settleAmount) {
        String orderTime = addDays(patient[6], dayOffset - 1);
        String execTime = addDays(patient[6], dayOffset);
        String orderId = nextId("MO", orderTime, ++orderSeq);
        his.update("INSERT INTO medical_order(order_id,inhos_no,item_code,item_name,order_type,order_status,doctor,create_time) VALUES(?,?,?,?,?,?,?,?)",
                orderId, patient[0], item.code(), item.name(), "检验", "1", patient[9], orderTime);
        String feeId = nextId("FEE", orderTime, ++feeSeq);
        his.update("INSERT INTO fee_detail(fee_id,inhos_no,order_id,item_code,item_name,amount,fee_status,charge_time) VALUES(?,?,?,?,?,?,?,?)",
                feeId, patient[0], orderId, item.code(), item.name(), item.price(), "1", execTime);
        charge.update("INSERT INTO charge_audit(fee_id,action,operator,op_time) VALUES(?,?,?,?)", feeId, "CHARGE", "收费机器人", execTime);
        settleAmount.merge(patient[0], BigDecimal.valueOf(item.price()), BigDecimal::add);
        String applyId = nextId("LA", orderTime, ++applySeq);
        lis.update("INSERT INTO lab_apply(apply_id,order_id,patient_no,patient_name,item_code,item_name,apply_status,apply_time,update_time) VALUES(?,?,?,?,?,?,?,?,?)",
                applyId, orderId, patient[0], patient[1], item.code(), item.name(), "P", orderTime, execTime);
        lis.update("INSERT INTO lab_report(report_id,apply_id,patient_no,item_code,result_status,report_time,reporter) VALUES(?,?,?,?,?,?,?)",
                nextId("LR", orderTime, ++reportSeq), applyId, patient[0], item.code(), resultStatus, execTime, "检验师 林芳");
    }

    // ---------- 正常执行医嘱：计费 + 按类型分流 LIS/药房/PACS ----------
    private void executedOrder(JdbcTemplate his, JdbcTemplate lis, JdbcTemplate charge, JdbcTemplate pharmacy,
                               JdbcTemplate pacs, JdbcTemplate nurse, String[] patient, Item item, int dayOffset,
                               Map<String, BigDecimal> settleAmount) {
        executedOrderWithExam(his, lis, charge, pharmacy, pacs, nurse, patient, item, dayOffset, settleAmount, "N", null);
    }

    private void executedOrderWithExam(JdbcTemplate his, JdbcTemplate lis, JdbcTemplate charge, JdbcTemplate pharmacy,
                                       JdbcTemplate pacs, JdbcTemplate nurse, String[] patient, Item item, int dayOffset,
                                       Map<String, BigDecimal> settleAmount, String examAbnormal, String examConclusion) {
        String orderTime = addDays(patient[6], dayOffset - 1);
        String execTime = addDays(patient[6], dayOffset);
        String orderId = nextId("MO", orderTime, ++orderSeq);
        his.update("INSERT INTO medical_order(order_id,inhos_no,item_code,item_name,order_type,order_status,doctor,create_time) VALUES(?,?,?,?,?,?,?,?)",
                orderId, patient[0], item.code(), item.name(), typeName(item), "1", patient[9], orderTime);

        String feeId = nextId("FEE", orderTime, ++feeSeq);
        his.update("INSERT INTO fee_detail(fee_id,inhos_no,order_id,item_code,item_name,amount,fee_status,charge_time) VALUES(?,?,?,?,?,?,?,?)",
                feeId, patient[0], orderId, item.code(), item.name(), item.price(), "1", execTime);
        charge.update("INSERT INTO charge_audit(fee_id,action,operator,op_time) VALUES(?,?,?,?)", feeId, "CHARGE", "收费机器人", execTime);
        settleAmount.merge(patient[0], BigDecimal.valueOf(item.price()), BigDecimal::add);

        if ("LAB".equals(item.type())) {
            String applyId = nextId("LA", orderTime, ++applySeq);
            lis.update("INSERT INTO lab_apply(apply_id,order_id,patient_no,patient_name,item_code,item_name,apply_status,apply_time,update_time) VALUES(?,?,?,?,?,?,?,?,?)",
                    applyId, orderId, patient[0], patient[1], item.code(), item.name(), "P", orderTime, execTime);
            lis.update("INSERT INTO lab_report(report_id,apply_id,patient_no,item_code,result_status,report_time,reporter) VALUES(?,?,?,?,?,?,?)",
                    nextId("LR", orderTime, ++reportSeq), applyId, patient[0], item.code(), "N", execTime, "检验师 林芳");
        } else if ("DRUG".equals(item.type())) {
            pharmacy.update("INSERT INTO dispense_record(dispense_id,order_id,patient_no,item_code,item_name,status,dispense_time,pharmacist) VALUES(?,?,?,?,?,?,?,?)",
                    nextId("DP", orderTime, ++dispenseSeq), orderId, patient[0], item.code(), item.name(), "1",
                    addHours(execTime, 4), PHARMACISTS[dispenseSeq % 3]);
        } else {
            // 检查：PACS 产出检查报告
            pacs.update("INSERT INTO exam_report(exam_id,order_id,patient_no,patient_name,item_code,item_name,abnormal_flag,conclusion,exam_time,report_time,technician,reviewer) VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                    nextId("EX", orderTime, ++examSeq), orderId, patient[0], patient[1], item.code(), item.name(),
                    examAbnormal,
                    examConclusion != null ? examConclusion : normalConclusion(item.code()),
                    execTime, addHours(execTime, 3), "技师 何斌", "影像医师 沈阅");
        }

        // 护士站执行确认（给药/采样/检查陪送）——「已执行」状态的业务来源
        String execType = switch (item.type()) {
            case "DRUG" -> "给药";
            case "LAB" -> "采样";
            default -> "检查陪送";
        };
        nurse.update("INSERT INTO nurse_exec(exec_id,order_id,inhos_no,exec_type,exec_time,nurse,exec_status) VALUES(?,?,?,?,?,?,?)",
                nextId("NE", orderTime, ++nurseSeq), orderId, patient[0], execType, execTime, NURSES[nurseSeq % 3], "1");
    }

    private int examSeq = 0;
    private int nurseSeq = 0;
    private static final String[] NURSES = {"护士 张丽", "护士 王芳", "护士 李晓"};

    private String normalConclusion(String itemCode) {
        return switch (itemCode) {
            case "R001" -> "胸廓对称，双肺纹理清晰，心肺膈未见明显异常。";
            case "R002" -> "胸片示两肺野清晰，心影大小形态正常。";
            default -> "腹部超声示肝胆胰脾未见明显异常回声。";
        };
    }

    /** 故障记录：检验医嘱已取消 + 费用仍正常 + LIS 申请状态 C（升级后撤销码，适配器无映射未退费） */
    private void faultRecord(JdbcTemplate his, JdbcTemplate lis, JdbcTemplate charge,
                             String[] patient, Item item, String orderTime, String cancelTime,
                             Map<String, BigDecimal> settleAmount) {
        String orderId = nextId("MO", orderTime, ++orderSeq);
        his.update("INSERT INTO medical_order(order_id,inhos_no,item_code,item_name,order_type,order_status,doctor,create_time,cancel_time) VALUES(?,?,?,?,?,?,?,?,?)",
                orderId, patient[0], item.code(), item.name(), "检验", "2", patient[9], orderTime, cancelTime);

        String feeId = nextId("FEE", orderTime, ++feeSeq);
        his.update("INSERT INTO fee_detail(fee_id,inhos_no,order_id,item_code,item_name,amount,fee_status,charge_time) VALUES(?,?,?,?,?,?,?,?)",
                feeId, patient[0], orderId, item.code(), item.name(), item.price(), "1", orderTime);
        charge.update("INSERT INTO charge_audit(fee_id,action,operator,op_time) VALUES(?,?,?,?)", feeId, "CHARGE", "收费机器人", orderTime);
        settleAmount.merge(patient[0], BigDecimal.valueOf(item.price()), BigDecimal::add);

        lis.update("INSERT INTO lab_apply(apply_id,order_id,patient_no,patient_name,item_code,item_name,apply_status,apply_time,update_time) VALUES(?,?,?,?,?,?,?,?,?)",
                nextId("LA", orderTime, ++applySeq), orderId, patient[0], patient[1], item.code(), item.name(), "C", orderTime, cancelTime);
    }

    /** 历史对照：检验医嘱取消 + 费用已退费 + LIS 申请状态 X（升级前撤销码，适配器正常工作） */
    private void historicalRefunded(JdbcTemplate his, JdbcTemplate lis, JdbcTemplate charge,
                                    String[] patient, Item item, String orderTime, String cancelTime) {
        String orderId = nextId("MO", orderTime, ++orderSeq);
        his.update("INSERT INTO medical_order(order_id,inhos_no,item_code,item_name,order_type,order_status,doctor,create_time,cancel_time) VALUES(?,?,?,?,?,?,?,?,?)",
                orderId, patient[0], item.code(), item.name(), "检验", "2", patient[9], orderTime, cancelTime);

        String feeId = nextId("FEE", orderTime, ++feeSeq);
        his.update("INSERT INTO fee_detail(fee_id,inhos_no,order_id,item_code,item_name,amount,fee_status,charge_time) VALUES(?,?,?,?,?,?,?,?)",
                feeId, patient[0], orderId, item.code(), item.name(), item.price(), "2", orderTime);
        charge.update("INSERT INTO charge_audit(fee_id,action,operator,op_time) VALUES(?,?,?,?)", feeId, "CHARGE", "收费机器人", orderTime);
        charge.update("INSERT INTO charge_audit(fee_id,action,operator,op_time) VALUES(?,?,?,?)", feeId, "REFUND", "收费机器人", cancelTime);
        charge.update("INSERT INTO refund_apply(refund_id,inhos_no,fee_id,amount,reason,apply_time,status) VALUES(?,?,?,?,?,?,?)",
                nextId("RA", orderTime, ++refundSeq), patient[0], feeId, item.price(), "检验撤销自动退费", cancelTime, "2");

        lis.update("INSERT INTO lab_apply(apply_id,order_id,patient_no,patient_name,item_code,item_name,apply_status,apply_time,update_time) VALUES(?,?,?,?,?,?,?,?,?)",
                nextId("LA", orderTime, ++applySeq), orderId, patient[0], patient[1], item.code(), item.name(), "X", orderTime, cancelTime);
    }

    /** 药品医嘱取消：费用已退费 + 药房已退药（药品线闭环不受 LIS 故障影响） */
    private void drugCanceledRefunded(JdbcTemplate his, JdbcTemplate charge, JdbcTemplate pharmacy,
                                      String[] patient, Item item, String orderTime, int idx) {
        String cancelTime = addHours(orderTime, 5);
        String orderId = nextId("MO", orderTime, ++orderSeq);
        his.update("INSERT INTO medical_order(order_id,inhos_no,item_code,item_name,order_type,order_status,doctor,create_time,cancel_time) VALUES(?,?,?,?,?,?,?,?,?)",
                orderId, patient[0], item.code(), item.name(), "药品", "2", patient[9], orderTime, cancelTime);

        String feeId = nextId("FEE", orderTime, ++feeSeq);
        his.update("INSERT INTO fee_detail(fee_id,inhos_no,order_id,item_code,item_name,amount,fee_status,charge_time) VALUES(?,?,?,?,?,?,?,?)",
                feeId, patient[0], orderId, item.code(), item.name(), item.price(), "2", orderTime);
        charge.update("INSERT INTO charge_audit(fee_id,action,operator,op_time) VALUES(?,?,?,?)", feeId, "CHARGE", "收费机器人", orderTime);
        charge.update("INSERT INTO charge_audit(fee_id,action,operator,op_time) VALUES(?,?,?,?)", feeId, "REFUND", "人工-收费处", cancelTime);

        pharmacy.update("INSERT INTO dispense_record(dispense_id,order_id,patient_no,item_code,item_name,status,dispense_time,pharmacist) VALUES(?,?,?,?,?,?,?,?)",
                nextId("DP", orderTime, ++dispenseSeq), orderId, patient[0], item.code(), item.name(), "2", cancelTime,
                PHARMACISTS[idx % 3]);
    }

    private void prepay(JdbcTemplate charge, String[] patient, int amount, String channel, int idx) {
        charge.update("INSERT INTO pay_record(pay_id,inhos_no,amount,pay_type,channel,pay_time,operator) VALUES(?,?,?,?,?,?,?)",
                nextId("PAY", patient[6], ++paySeq), patient[0], amount, "1", channel, addHours(patient[6], 1), "收费处 小周");
    }

    private void settlePay(JdbcTemplate charge, String[] patient, BigDecimal total, String settleId) {
        List<BigDecimal> prepay = charge.queryForList(
                "SELECT IFNULL(SUM(amount),0) FROM pay_record WHERE inhos_no=? AND pay_type='1'", BigDecimal.class, patient[0]);
        BigDecimal diff = total.subtract(prepay.get(0));
        if (diff.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }
        String payId = nextId("PAY", patient[7], ++paySeq);
        if (diff.compareTo(BigDecimal.ZERO) > 0) {
            charge.update("INSERT INTO pay_record(pay_id,inhos_no,amount,pay_type,channel,pay_time,operator) VALUES(?,?,?,?,?,?,?)",
                    payId, patient[0], diff, "2", "扫码", patient[7], "收费处 小周");
        } else {
            charge.update("INSERT INTO pay_record(pay_id,inhos_no,amount,pay_type,channel,pay_time,operator) VALUES(?,?,?,?,?,?,?)",
                    payId, patient[0], diff.abs(), "3", "现金", patient[7], "收费处 小周");
        }
    }

    private void wipe(JdbcTemplate his, JdbcTemplate lis, JdbcTemplate charge, JdbcTemplate pharmacy,
                      JdbcTemplate opd, JdbcTemplate emr, JdbcTemplate pacs, JdbcTemplate nurse) {
        for (String t : List.of("fee_detail", "medical_order", "inpatient", "status_map")) {
            his.update("DELETE FROM " + t);
        }
        for (String t : List.of("lab_report", "lab_apply", "lab_dict_status")) {
            lis.update("DELETE FROM " + t);
        }
        for (String t : List.of("charge_audit", "refund_apply", "settlement", "pay_record")) {
            charge.update("DELETE FROM " + t);
        }
        pharmacy.update("DELETE FROM dispense_record");
        pharmacy.update("DELETE FROM drug_dict");
        for (String t : List.of("purchase_order", "stock_in", "stock_out", "drug_stock", "presc_review")) {
            pharmacy.update("DELETE FROM " + t);
        }
        for (String t : List.of("invoice", "reconcile_record")) {
            charge.update("DELETE FROM " + t);
        }
        for (String t : List.of("opd_presc", "opd_visit", "opd_reg")) {
            opd.update("DELETE FROM " + t);
        }
        for (String t : List.of("emr_record", "emr_surgery", "diag_dict", "patient_allergy", "anesthesia_record", "drg_group")) {
            emr.update("DELETE FROM " + t);
        }
        pacs.update("DELETE FROM exam_report");
        nurse.update("DELETE FROM nurse_exec");
        nurse.update("DELETE FROM vital_sign");
        for (String t : List.of("dept", "staff", "blood_apply", "blood_transfusion")) {
            his.update("DELETE FROM " + t);
        }
        for (String t : List.of("infection_report", "followup")) {
            emr.update("DELETE FROM " + t);
        }
        JdbcTemplate material = datasourceService.jdbc("DS_MATERIAL");
        for (String t : List.of("material_stock", "material_apply", "material_in", "material_out")) {
            material.update("DELETE FROM " + t);
        }
    }

    /** 40 名虚构患者：住院号/姓名/性别/年龄/病区/科室/入院/出院/状态/医生 */
    private List<String[]> patients() {
        List<String[]> list = new ArrayList<>(List.of(
                new String[]{"ZY20260815001", "张建国", "男", "58", "内一科病区", "心内科", "2026-08-15 09:00:00", "2026-08-30 10:00:00", "出院", "王海涛"},
                new String[]{"ZY20260812002", "李红梅", "女", "45", "外二科病区", "普外科", "2026-08-12 14:00:00", "2026-08-25 11:00:00", "出院", "刘建军"},
                new String[]{"ZY20260816003", "王强", "男", "67", "内一科病区", "呼吸内科", "2026-08-16 08:30:00", "2026-09-02 09:30:00", "出院", "赵文静"},
                new String[]{"ZY20260818004", "王秀兰", "女", "52", "内一科病区", "消化内科", "2026-08-18 10:20:00", "2026-09-04 15:00:00", "出院", "陈志远"},
                new String[]{"ZY20260820005", "刘伟", "男", "34", "骨科病区", "骨科", "2026-08-20 16:00:00", null, "在院", "刘建军"},
                new String[]{"ZY20260805006", "陈芳", "女", "71", "内一科病区", "心内科", "2026-08-05 09:40:00", "2026-08-18 10:30:00", "出院", "王海涛"},
                new String[]{"ZY20260822007", "杨光", "男", "29", "外二科病区", "普外科", "2026-08-22 11:10:00", null, "在院", "刘建军"},
                new String[]{"ZY20260810008", "赵敏", "女", "60", "骨科病区", "骨科", "2026-08-10 13:00:00", "2026-08-22 09:00:00", "出院", "孙立军"},
                new String[]{"ZY20260825009", "周杰", "男", "48", "内一科病区", "呼吸内科", "2026-08-25 08:00:00", null, "在院", "赵文静"},
                new String[]{"ZY20260828010", "吴丽丽", "女", "39", "内一科病区", "消化内科", "2026-08-28 15:30:00", null, "在院", "陈志远"},
                new String[]{"ZY20260802011", "郑国庆", "男", "55", "外二科病区", "普外科", "2026-08-02 09:00:00", "2026-08-14 10:00:00", "出院", "刘建军"},
                new String[]{"ZY20260728012", "冯雪", "女", "63", "内一科病区", "心内科", "2026-07-28 10:00:00", "2026-08-10 09:30:00", "出院", "王海涛"}
        ));
        String[][] extra = {
                {"孙志强", "男", "61"}, {"林秀英", "女", "55"}, {"黄海波", "男", "42"}, {"徐雅静", "女", "33"},
                {"胡永刚", "男", "70"}, {"郭晓燕", "女", "47"}, {"何丽华", "女", "58"}, {"马文轩", "男", "26"},
                {"罗建军", "男", "53"}, {"梁桂香", "女", "66"}, {"宋明辉", "男", "38"}, {"唐雅琴", "女", "44"},
                {"韩雪梅", "女", "51"}, {"曹建华", "男", "59"}, {"谢文娟", "女", "30"}, {"邓立新", "男", "64"},
                {"许桂英", "女", "72"}, {"傅国强", "男", "49"}, {"沈丽萍", "女", "41"}, {"彭大勇", "男", "36"},
                {"卢晓东", "男", "57"}, {"蔡文静", "女", "28"}, {"贾宏伟", "男", "62"}, {"潘素芬", "女", "54"},
                {"蒋卫东", "男", "46"}, {"任晓峰", "男", "31"}, {"魏淑华", "女", "68"}, {"吕海军", "男", "50"}};
        String[][] wards = {{"内一科病区", "心内科"}, {"内一科病区", "呼吸内科"}, {"内一科病区", "消化内科"},
                {"外二科病区", "普外科"}, {"骨科病区", "骨科"}};
        String[] doctors = {"王海涛", "刘建军", "赵文静", "陈志远", "孙立军", "林晓东"};
        for (int i = 0; i < extra.length; i++) {
            int idx = i + 13;
            LocalDateTime admit = LocalDateTime.of(2026, 8, 25, 8, 0).plusDays(i % 15).plusHours(i % 9);
            int stay = 6 + i % 8;
            LocalDateTime discharge = admit.plusDays(stay);
            boolean discharged = discharge.isBefore(LocalDateTime.of(2026, 9, 9, 23, 59));
            String[] ward = wards[i % wards.length];
            list.add(new String[]{
                    String.format("ZY2026%08d", 82000000 + idx),
                    extra[i][0], extra[i][1], extra[i][2],
                    ward[0], ward[1],
                    admit.format(FMT), discharged ? discharge.format(FMT) : null,
                    discharged ? "出院" : "在院", doctors[i % doctors.length]});
        }
        // 第 41 名：儿童患者（SHACL Shape 3 儿童禁用案例）——韩小梅 8 岁，布洛芬缓释胶囊（12 岁以下不宜）
        list.add(new String[]{"ZY20260903041", "韩小梅", "女", "8", "内一科病区", "儿科",
                "2026-09-03 09:00:00", "2026-09-10 10:00:00", "出院", "王海涛"});
        return list;
    }

    private String typeName(Item item) {
        return switch (item.type()) {
            case "LAB" -> "检验";
            case "DRUG" -> "药品";
            default -> "检查";
        };
    }

    private String nextId(String prefix, String datetime, int seq) {
        return prefix + datetime.substring(0, 10).replace("-", "") + String.format("%04d", seq);
    }

    private String addDays(String datetime, int days) {
        return LocalDateTime.parse(datetime, FMT).plusDays(days).format(FMT);
    }

    private String addHours(String datetime, int hours) {
        return LocalDateTime.parse(datetime, FMT).plusHours(hours).format(FMT);
    }
}
