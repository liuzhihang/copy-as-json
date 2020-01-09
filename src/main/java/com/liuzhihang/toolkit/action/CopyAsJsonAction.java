package com.liuzhihang.toolkit.action;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.intellij.lang.jvm.JvmClassKind;
import com.intellij.notification.*;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.actionSystem.CommonDataKeys;
import com.intellij.openapi.actionSystem.PlatformDataKeys;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.psi.util.PsiTypesUtil;
import com.intellij.psi.util.PsiUtil;
import com.liuzhihang.toolkit.utils.GsonFormatUtil;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.*;

/**
 * JavaBean 复制为Json字符串
 *
 * @author liuzhihang
 * @date 2019/5/5 13:42
 */
public class CopyAsJsonAction extends AnAction {

    private static final NotificationGroup NOTIFICATION_GROUP = new NotificationGroup("Java2Json.NotificationGroup", NotificationDisplayType.BALLOON, true);

    @NonNls
    private static final Map<String, Object> PROPERTIES_TYPES = new HashMap<>(16);
    @NonNls
    private static final Set<String> ANNOTATION_TYPES = new HashSet<>();

    static {
        // 包装数据类型
        PROPERTIES_TYPES.put("Byte", 0);
        PROPERTIES_TYPES.put("Short", 0);
        PROPERTIES_TYPES.put("Integer", 0);
        PROPERTIES_TYPES.put("Long", 0L);
        PROPERTIES_TYPES.put("Float", 0.0F);
        PROPERTIES_TYPES.put("Double", 0.0D);
        PROPERTIES_TYPES.put("Boolean", false);
        // 其他
        PROPERTIES_TYPES.put("String", "");
        PROPERTIES_TYPES.put("BigDecimal", null);
        PROPERTIES_TYPES.put("Date", null);
        PROPERTIES_TYPES.put("LocalDate", null);
        PROPERTIES_TYPES.put("LocalTime", null);
        PROPERTIES_TYPES.put("LocalDateTime", null);

        // 注解过滤
        ANNOTATION_TYPES.add("javax.annotation.Resource");
        ANNOTATION_TYPES.add("org.springframework.beans.factory.annotation.Autowired");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent e) {

        Project project = e.getData(PlatformDataKeys.PROJECT);
        PsiFile psiFile = e.getData(CommonDataKeys.PSI_FILE);
        Editor editor = e.getData(CommonDataKeys.EDITOR);

        PsiElement referenceAt = psiFile.findElementAt(editor.getCaretModel().getOffset());
        PsiClass selectedClass = (PsiClass) PsiTreeUtil.getContextOfType(referenceAt, new Class[]{PsiClass.class});
        try {
            Map fieldsMap = getFields(selectedClass);

            Gson gson = new GsonBuilder().create();
            String json = GsonFormatUtil.gsonFormat(gson, fieldsMap);

            // 使用自定义缩进格式 String json = new GsonBuilder().setPrettyPrinting().create().toJson(fieldsMap);
            StringSelection selection = new StringSelection(json);
            Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
            clipboard.setContents(selection, selection);
            String message = "Convert " + selectedClass.getName() + " to JSON success, copied to clipboard.";
            Notification success = NOTIFICATION_GROUP.createNotification(message, NotificationType.INFORMATION);
            Notifications.Bus.notify(success, project);
        } catch (Exception ex) {
            Notification error = NOTIFICATION_GROUP.createNotification("Convert to JSON failed.", NotificationType.ERROR);
            Notifications.Bus.notify(error, project);
        }

    }


    public static Map getFields(PsiClass psiClass) {

        Map<String, Object> fieldMap = new LinkedHashMap<>();
        // Map<String, Object> commentFieldMap = new LinkedHashMap<>();

        if (psiClass != null && psiClass.getClassKind() == JvmClassKind.CLASS) {
            for (PsiField field : psiClass.getAllFields()) {
                PsiType type = field.getType();
                String name = field.getName();
                // if (field.getDocComment() != null && StringUtils.isNotBlank(field.getDocComment().getText())) {
                //     String fieldComment = field.getDocComment().getText();
                //     commentFieldMap.put(name, CommentUtils.removeSymbol(fieldComment));
                // }
                // 判断注解 javax.annotation.Resource   org.springframework.beans.factory.annotation.Autowired
                PsiAnnotation[] annotations = field.getAnnotations();
                if (annotations.length > 0 && containsAnnotation(annotations)) {
                    fieldMap.put(name, "");
                } else if (type instanceof PsiPrimitiveType) {
                    // 基本类型
                    fieldMap.put(name, PsiTypesUtil.getDefaultValue(type));
                } else {
                    //reference Type
                    String fieldTypeName = type.getPresentableText();
                    // 指定的类型
                    if (PROPERTIES_TYPES.containsKey(fieldTypeName)) {
                        fieldMap.put(name, PROPERTIES_TYPES.get(fieldTypeName));
                    } else if (type instanceof PsiArrayType) {
                        //array type
                        List<Object> list = new ArrayList<>();
                        PsiType deepType = type.getDeepComponentType();
                        String deepTypeName = deepType.getPresentableText();
                        if (deepType instanceof PsiPrimitiveType) {
                            list.add(PsiTypesUtil.getDefaultValue(deepType));
                        } else if (PROPERTIES_TYPES.containsKey(deepTypeName)) {
                            list.add(PROPERTIES_TYPES.get(deepTypeName));
                        } else {
                            list.add(getFields(PsiUtil.resolveClassInType(deepType)));
                        }
                        fieldMap.put(name, list);
                    } else if (fieldTypeName.startsWith("List") || fieldTypeName.startsWith("ArrayList") || fieldTypeName.startsWith("Set") || fieldTypeName.startsWith("HashSet")) {
                        // List Set or HashSet
                        List<Object> list = new ArrayList<>();
                        PsiType iterableType = PsiUtil.extractIterableTypeParameter(type, false);
                        PsiClass iterableClass = PsiUtil.resolveClassInClassTypeOnly(iterableType);
                        if (iterableClass != null) {
                            String classTypeName = iterableClass.getName();
                            if (PROPERTIES_TYPES.containsKey(classTypeName)) {
                                list.add(PROPERTIES_TYPES.get(classTypeName));
                            } else {
                                list.add(getFields(iterableClass));
                            }
                        }
                        fieldMap.put(name, list);
                    } else if (fieldTypeName.startsWith("HashMap") || fieldTypeName.startsWith("Map")) {
                        // HashMap or Map
                        fieldMap.put(name, new HashMap<>(4));
                    } else if (PsiUtil.resolveClassInType(type).getClassKind() != JvmClassKind.CLASS) {
                        // enum or interface
                        fieldMap.put(name, "");
                    } else {
                        fieldMap.put(name, getFields(PsiUtil.resolveClassInType(type)));
                    }
                }
            }
            // json 串中的注释字段 暂时不添加
            // if (commentFieldMap.size() > 0) {
            //     fieldMap.put("@comment", commentFieldMap);
            // }
        }
        return fieldMap;
    }

    /**
     * 是否包含指定的注解
     *
     * @param annotations
     * @return
     */
    private static boolean containsAnnotation(PsiAnnotation[] annotations) {
        for (PsiAnnotation annotation : annotations) {
            if (ANNOTATION_TYPES.contains(annotation.getQualifiedName())) {
                return true;
            }
        }
        return false;
    }

}
