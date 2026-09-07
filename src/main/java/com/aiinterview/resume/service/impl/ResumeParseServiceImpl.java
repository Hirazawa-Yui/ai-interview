package com.aiinterview.resume.service.impl;

import com.aiinterview.common.BusinessException;
import com.aiinterview.common.ErrorCode;
import com.aiinterview.resume.service.IResumeParseService;
import lombok.extern.slf4j.Slf4j;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.parser.AutoDetectParser;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.sax.BodyContentHandler;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

/**
 * 简历解析服务实现（Apache Tika）
 */
@Slf4j
@Service
public class ResumeParseServiceImpl implements IResumeParseService {

    private static final int MAX_CHARS = 5_000_000; // 5MB 文本上限
    private final Tika tika = new Tika();

    @Override
    public String parse(MultipartFile file) {
        try (InputStream in = file.getInputStream()) {
            // 使用 AutoDetectParser 自动识别格式
            BodyContentHandler handler = new BodyContentHandler(MAX_CHARS);
            Metadata metadata = new Metadata();
            metadata.set("resourceName", file.getOriginalFilename());
            ParseContext context = new ParseContext();
            // 使用默认 Parser（已排除嵌入式文档解析，减少噪音）
            context.set(Parser.class, new AutoDetectParser());

            AutoDetectParser parser = new AutoDetectParser();
            parser.parse(in, handler, metadata, context);

            String text = handler.toString().trim();
            if (text.isEmpty()) {
                throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "无法从文件中提取文本");
            }
            log.info("简历解析完成: file={}, chars={}", file.getOriginalFilename(), text.length());
            return cleanText(text);
        } catch (IOException e) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "文件读取失败: " + e.getMessage());
        } catch (TikaException | org.xml.sax.SAXException e) {
            throw new BusinessException(ErrorCode.RESUME_PARSE_FAILED, "文件解析失败: " + e.getMessage());
        }
    }

    @Override
    public String detectContentType(MultipartFile file) {
        try {
            return tika.detect(file.getInputStream(), file.getOriginalFilename());
        } catch (IOException e) {
            return "application/octet-stream";
        }
    }

    /**
     * 简单清洗：去掉控制字符和连续空行
     */
    private String cleanText(String text) {
        return text
                .replaceAll("[\\x00-\\x08\\x0B\\x0C\\x0E-\\x1F]", "")  // 去控制字符（保留\t\n）
                .replaceAll("\\n{3,}", "\n\n")  // 合并多余空行
                .trim();
    }
}
