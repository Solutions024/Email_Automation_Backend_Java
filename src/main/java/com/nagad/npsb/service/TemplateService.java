package com.nagad.npsb.service;

import com.nagad.npsb.config.LiveSettings;
import com.nagad.npsb.dto.TemplateRequest;
import com.nagad.npsb.dto.VariableRequest;
import com.nagad.npsb.entity.ChangeRequest;
import com.nagad.npsb.entity.EmailTemplate;
import com.nagad.npsb.entity.TemplateVariable;
import com.nagad.npsb.entity.User;
import com.nagad.npsb.repository.EmailTemplateRepository;
import com.nagad.npsb.repository.TemplateVariableRepository;
import com.nagad.npsb.service.dispatch.Sql;
import com.nagad.npsb.service.dispatch.UserNames;
import com.nagad.npsb.util.Py;
import com.nagad.npsb.web.ApiException;
import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import static com.nagad.npsb.service.dispatch.UserNames.name;

/**
 * Email templates (several per mail type; one default per mail type and purpose) and template variables.
 * Templates belong to the users who prepare the emails; admins only read (and approve) them. With maker-checker on,
 * users' changes wait for an admin's approval (Approvals).
 */
@Service
public class TemplateService {
    private final Approvals approvals;
    private final LiveSettings live;
    private final Sql sql;
    private final UserNames userNames;
    private final EntityManager em;
    private final EmailTemplateRepository templates;
    private final TemplateVariableRepository variables;

    public TemplateService(Approvals approvals, LiveSettings live, Sql sql, UserNames userNames, EntityManager em,
                           EmailTemplateRepository templates, TemplateVariableRepository variables) {
        this.approvals = approvals;
        this.live = live;
        this.sql = sql;
        this.userNames = userNames;
        this.em = em;
        this.templates = templates;
        this.variables = variables;
    }

    @PostConstruct
    void registerApprovals() {
        approvals.register("template", this::templateSnapshot, Map.<String, Approvals.Apply>of(
                "create", this::applyTemplateCreate, "update", this::applyTemplateUpdate,
                "default", this::applyTemplateDefault, "delete", this::applyTemplateDelete));
        approvals.register("variable", this::variableSnapshot, Map.<String, Approvals.Apply>of(
                "create", this::applyVariableCreate, "update", this::applyVariableUpdate, "delete", this::applyVariableDelete));
    }

    // ---------------------------------------------------------------- templates used for the emails

    /** The email's own template if it still exists, else the default of its mail type (null: no template). */
    public EmailTemplate templateFor(String mailType, Integer templateId) {
        EmailTemplate template = templateId != null && templateId != 0 ? templates.findById(templateId).orElse(null) : null;
        if (template == null || !template.getMailType().equals(mailType) || !"initial".equals(template.getPurpose())) {
            template = sql.first(EmailTemplate.class, "WHERE t.mail_type = ? AND t.purpose = 'initial' AND t.is_default = ?", mailType, true);
        }
        return template;
    }

    /** The default reminder (follow-up) template of a mail type, if there is one. */
    public EmailTemplate reminderTemplateFor(String mailType) {
        String type = "outward_reminder".equals(mailType) ? "outward" : mailType;
        return sql.first(EmailTemplate.class, "WHERE t.mail_type = ? AND t.purpose = 'reminder' AND t.is_default = ?", type, true);
    }

    public List<TemplateVariable> templateVariables() {
        return sql.jpql(TemplateVariable.class, "SELECT t FROM TemplateVariable t ORDER BY t.name", 0);
    }

    // ---------------------------------------------------------------- the Email Templates page

    static Map<String, Object> templateToDict(EmailTemplate t, Map<String, String> names) {
        return Py.map(
                "id", t.getId(),
                "name", t.getName(),
                "mail_type", t.getMailType(),
                "mail_type_label", EmailService.MAIL_TYPE_NAMES.getOrDefault(t.getMailType(), t.getMailType()),
                "purpose", t.getPurpose(),
                "description", t.getDescription(),
                "subject", t.getSubject(),
                "body_html", t.getBodyHtml(),
                "is_default", t.getIsDefault(),
                "created_by_name", name(names, t.getCreatedBy()),
                "created_at", Py.iso(t.getCreatedAt()),
                "updated_by_name", name(names, t.getUpdatedBy()),
                "updated_at", Py.iso(t.getUpdatedAt()));
    }

    void checkTemplate(TemplateRequest data, Integer templateId) {
        List<String> known = new ArrayList<>();
        for (TemplateVariable v : templateVariables()) known.add(v.getName());
        if ("reminder".equals(data.purpose())) known.addAll(EmailService.REMINDER_PLACEHOLDERS.keySet());
        List<String> unknown = EmailService.unknownPlaceholders(known, data.subject(), data.bodyHtml());
        if (!unknown.isEmpty()) {
            List<String> shown = new ArrayList<>(), use = new ArrayList<>();
            for (String u : unknown) shown.add("{{" + u + "}}");
            for (String p : EmailService.PLACEHOLDERS.keySet()) use.add("{{" + p + "}}");
            for (String k : known) use.add("{{" + k + "}}");
            throw new ApiException(400, "Unknown placeholder(s): " + String.join(", ", shown) + ". Use: " + String.join(", ", use) + ".");
        }
        for (EmailTemplate other : sql.all(EmailTemplate.class, "WHERE t.id != ?", templateId == null ? 0 : templateId)) {
            if (Py.casefold(other.getName()).equals(Py.casefold(data.name()))) {
                throw new ApiException(400, "A template named \"" + other.getName() + "\" already exists.");
            }
        }
    }

    /** The only default of its mail type and purpose. */
    void makeDefault(EmailTemplate template) {
        for (EmailTemplate other : sql.all(EmailTemplate.class, "WHERE t.mail_type = ? AND t.purpose = ? AND t.id != ?",
                template.getMailType(), template.getPurpose(), template.getId())) {
            other.setIsDefault(false);
        }
        template.setIsDefault(true);
    }

    List<Map<String, Object>> listTemplatesDicts() {
        List<EmailTemplate> all = sql.all(EmailTemplate.class, "ORDER BY t.mail_type, t.purpose, t.is_default DESC, t.name");
        List<String> who = new ArrayList<>();
        for (EmailTemplate t : all) {
            who.add(t.getCreatedBy());
            who.add(t.getUpdatedBy());
        }
        Map<String, String> names = userNames.userNames(who);
        List<Map<String, Object>> out = new ArrayList<>();
        for (EmailTemplate t : all) out.add(templateToDict(t, names));
        return out;
    }

    static Map<String, Object> variableToDict(TemplateVariable v) {
        return Py.map("id", v.getId(), "name", v.getName(), "description", v.getDescription(), "source", v.getSource(),
                "value", v.getValue(), "column", v.getColumn(), "aggregate", v.getAggregate());
    }

    /** Everything the Email Templates page shows, with the changes still waiting for approval. */
    public Map<String, Object> templatesPayload(User user, Map<String, Object> result) {
        List<ChangeRequest> pending = sql.jpql(ChangeRequest.class,
                "SELECT t FROM ChangeRequest t WHERE t.entity IN ('template', 'variable') AND t.status = 'pending' ORDER BY t.id", 0);
        List<String> who = new ArrayList<>();
        for (ChangeRequest r : pending) who.add(r.getRequestedBy());
        Map<String, String> names = userNames.userNames(who);
        List<Object> placeholders = new ArrayList<>(), reminders = new ArrayList<>(), vars = new ArrayList<>(), pendingDicts = new ArrayList<>();
        EmailService.PLACEHOLDERS.forEach((k, v) -> placeholders.add(Py.map("name", k, "description", v)));
        EmailService.REMINDER_PLACEHOLDERS.forEach((k, v) -> reminders.add(Py.map("name", k, "description", v)));
        for (TemplateVariable v : templateVariables()) vars.add(variableToDict(v));
        for (ChangeRequest r : pending) pendingDicts.add(Approvals.toDict(r, names, user));
        return Py.map(
                "templates", listTemplatesDicts(),
                "placeholders", placeholders,
                "reminder_placeholders", reminders,
                "variables", vars,
                "variable_columns", new ArrayList<>(EmailService.VARIABLE_COLUMNS.keySet()),
                "variable_aggregates", EmailService.VARIABLE_AGGREGATES,
                "pending", pendingDicts,
                "maker_checker", live.makerChecker(),
                "result", result);
    }

    // ---------------------------------------------------------------- templates: maker-checker

    Map<String, Object> templateSnapshot(int templateId) {
        EmailTemplate t = templates.findById(templateId).orElse(null);
        if (t == null) return null;
        return Py.map("name", t.getName(), "mail_type", t.getMailType(), "purpose", t.getPurpose(), "description", t.getDescription(),
                "subject", t.getSubject(), "body_html", t.getBodyHtml(), "is_default", t.getIsDefault());
    }

    void checkTemplateChange(EmailTemplate template, TemplateRequest data) {
        checkTemplate(data, template.getId());
        if (Boolean.TRUE.equals(template.getIsDefault())
                && (!data.mailType().equals(template.getMailType()) || !data.purpose().equals(template.getPurpose()))) {
            throw new ApiException(400, "This is the default template of its mail type. "
                    + "Make another template the default first, then change its mail type.");
        }
        if (Boolean.TRUE.equals(template.getIsDefault()) && !data.isDefault()) {
            throw new ApiException(400, "Every mail type needs a default template. Make another template the default instead.");
        }
    }

    Integer applyTemplateCreate(Map<String, Object> after, Integer id, String by) {
        TemplateRequest data = TemplateRequest.fromStored(after);
        checkTemplate(data, null);
        EmailTemplate template = new EmailTemplate();
        template.setName(data.name());
        template.setMailType(data.mailType());
        template.setPurpose(data.purpose());
        template.setDescription(data.description());
        template.setSubject(data.subject());
        template.setBodyHtml(data.bodyHtml());
        template.setCreatedBy(by);
        template.setUpdatedBy(by);
        templates.save(template);
        em.flush();
        long hasDefault = sql.count("SELECT COUNT(*) FROM email_templates WHERE mail_type = ? AND purpose = ? AND is_default = ? AND id != ?",
                data.mailType(), data.purpose(), true, template.getId());
        if (data.isDefault() || hasDefault == 0) makeDefault(template);  // the first template of a mail type is its default
        return template.getId();
    }

    public EmailTemplate getTemplateOr404(int templateId) {
        EmailTemplate template = templates.findById(templateId).orElse(null);
        if (template == null) throw new ApiException(404, "Template not found");
        return template;
    }

    Integer applyTemplateUpdate(Map<String, Object> after, Integer templateId, String by) {
        EmailTemplate template = getTemplateOr404(templateId);
        TemplateRequest data = TemplateRequest.fromStored(after);
        checkTemplateChange(template, data);
        template.setName(data.name());
        template.setMailType(data.mailType());
        template.setPurpose(data.purpose());
        template.setDescription(data.description());
        template.setSubject(data.subject());
        template.setBodyHtml(data.bodyHtml());
        template.setUpdatedBy(by);
        if (data.isDefault()) makeDefault(template);
        return template.getId();
    }

    Integer applyTemplateDefault(Map<String, Object> after, Integer templateId, String by) {
        EmailTemplate template = getTemplateOr404(templateId);
        makeDefault(template);
        template.setUpdatedBy(by);
        return template.getId();
    }

    static void checkTemplateDelete(EmailTemplate template) {
        if (Boolean.TRUE.equals(template.getIsDefault())) {
            throw new ApiException(400, "The default template cannot be deleted. Make another template the default first.");
        }
    }

    /** Drafts that used it go back to the default template the next time previews are refreshed. */
    Integer applyTemplateDelete(Map<String, Object> after, Integer templateId, String by) {
        EmailTemplate template = getTemplateOr404(templateId);
        checkTemplateDelete(template);
        sql.execute("UPDATE email_dispatches SET template_id = NULL WHERE template_id = ?", template.getId());
        templates.delete(template);
        return templateId;
    }

    // ---------------------------------------------------------------- templates: endpoints

    public Map<String, Object> createTemplate(User user, TemplateRequest data) {
        checkTemplate(data, null);
        Map<String, Object> result = approvals.submit(user, "template", "create", null, data.name(), data.dump());
        return templatesPayload(user, result);
    }

    public Map<String, Object> updateTemplate(User user, int templateId, TemplateRequest data) {
        EmailTemplate template = getTemplateOr404(templateId);
        checkTemplateChange(template, data);
        Map<String, Object> result = approvals.submit(user, "template", "update", templateId, data.name(), data.dump());
        return templatesPayload(user, result);
    }

    public Map<String, Object> setDefaultTemplate(User user, int templateId) {
        EmailTemplate template = getTemplateOr404(templateId);
        Map<String, Object> result = approvals.submit(user, "template", "default", templateId, template.getName(), new LinkedHashMap<>());
        return templatesPayload(user, result);
    }

    public Map<String, Object> deleteTemplate(User user, int templateId) {
        EmailTemplate template = getTemplateOr404(templateId);
        checkTemplateDelete(template);
        Map<String, Object> result = approvals.submit(user, "template", "delete", templateId, template.getName(), new LinkedHashMap<>());
        return templatesPayload(user, result);
    }

    // ---------------------------------------------------------------- template variables

    void checkVariable(VariableRequest data, Integer variableId) {
        if ("column".equals(data.source())) {
            if (data.column() == null || !EmailService.VARIABLE_COLUMNS.containsKey(data.column())) {
                throw new ApiException(400, "Choose a column of the uploaded file.");
            }
            if (data.aggregate() == null || !EmailService.VARIABLE_AGGREGATES.containsKey(data.aggregate())) {
                throw new ApiException(400, "Choose how the values are combined.");
            }
            if ("sum".equals(data.aggregate()) && !"TXN_AMOUNT".equals(data.column())) throw new ApiException(400, "Only TXN_AMOUNT can be added up.");
        }
        List<TemplateVariable> other = sql.jpql(TemplateVariable.class, "SELECT t FROM TemplateVariable t WHERE t.name = ?1 AND t.id <> ?2", 1,
                data.name(), variableId == null ? 0 : variableId);
        if (!other.isEmpty()) throw new ApiException(400, "A variable named {{" + data.name() + "}} already exists.");
    }

    /** Names of the templates whose subject or body use {{name}}. */
    List<String> templatesUsing(String name) {
        Pattern pattern = Pattern.compile("\\{\\{\\s*" + Pattern.quote(name) + "\\s*\\}\\}", Pattern.UNICODE_CHARACTER_CLASS);
        List<String> out = new ArrayList<>();
        for (EmailTemplate t : sql.all(EmailTemplate.class, "ORDER BY t.id")) {
            if (pattern.matcher(t.getSubject()).find() || pattern.matcher(t.getBodyHtml()).find()) out.add(t.getName());
        }
        return out;
    }

    Map<String, Object> variableSnapshot(int variableId) {
        TemplateVariable v = variables.findById(variableId).orElse(null);
        if (v == null) return null;
        Map<String, Object> m = variableToDict(v);
        m.remove("id");
        return m;
    }

    TemplateVariable getVariableOr404(int variableId) {
        TemplateVariable v = variables.findById(variableId).orElse(null);
        if (v == null) throw new ApiException(404, "Variable not found");
        return v;
    }

    void checkVariableChange(TemplateVariable v, VariableRequest data) {
        checkVariable(data, v.getId());
        if (!data.name().equals(v.getName())) {
            List<String> used = templatesUsing(v.getName());
            if (!used.isEmpty()) {
                throw new ApiException(400, "{{" + v.getName() + "}} is used in: " + String.join(", ", used) + ". "
                        + "It can't be renamed; create a new variable instead.");
            }
        }
    }

    Integer applyVariableCreate(Map<String, Object> after, Integer id, String by) {
        VariableRequest data = VariableRequest.fromStored(after);
        checkVariable(data, null);
        TemplateVariable v = new TemplateVariable();
        v.setName(data.name());
        v.setDescription(data.description());
        v.setSource(data.source());
        v.setValue("text".equals(data.source()) ? data.value() : "");
        v.setColumn("column".equals(data.source()) ? data.column() : null);
        v.setAggregate("column".equals(data.source()) ? data.aggregate() : null);
        v.setCreatedBy(by);
        variables.save(v);
        em.flush();
        return v.getId();
    }

    Integer applyVariableUpdate(Map<String, Object> after, Integer variableId, String by) {
        TemplateVariable v = getVariableOr404(variableId);
        VariableRequest data = VariableRequest.fromStored(after);
        checkVariableChange(v, data);
        v.setName(data.name());
        v.setDescription(data.description());
        v.setSource(data.source());
        v.setValue("text".equals(data.source()) ? data.value() : "");
        v.setColumn("column".equals(data.source()) ? data.column() : null);
        v.setAggregate("column".equals(data.source()) ? data.aggregate() : null);
        return v.getId();
    }

    void checkVariableDelete(TemplateVariable v) {
        List<String> used = templatesUsing(v.getName());
        if (!used.isEmpty()) {
            throw new ApiException(400, "{{" + v.getName() + "}} is used in: " + String.join(", ", used) + ". Remove it from those templates first.");
        }
    }

    Integer applyVariableDelete(Map<String, Object> after, Integer variableId, String by) {
        TemplateVariable v = getVariableOr404(variableId);
        checkVariableDelete(v);
        variables.delete(v);
        return variableId;
    }

    public Map<String, Object> createVariable(User user, VariableRequest data) {
        checkVariable(data, null);
        Map<String, Object> result = approvals.submit(user, "variable", "create", null, "{{" + data.name() + "}}", data.dump());
        return templatesPayload(user, result);
    }

    public Map<String, Object> updateVariable(User user, int variableId, VariableRequest data) {
        TemplateVariable v = getVariableOr404(variableId);
        checkVariableChange(v, data);
        Map<String, Object> result = approvals.submit(user, "variable", "update", variableId, "{{" + data.name() + "}}", data.dump());
        return templatesPayload(user, result);
    }

    public Map<String, Object> deleteVariable(User user, int variableId) {
        TemplateVariable v = getVariableOr404(variableId);
        checkVariableDelete(v);
        Map<String, Object> result = approvals.submit(user, "variable", "delete", variableId, "{{" + v.getName() + "}}", new LinkedHashMap<>());
        return templatesPayload(user, result);
    }
}
