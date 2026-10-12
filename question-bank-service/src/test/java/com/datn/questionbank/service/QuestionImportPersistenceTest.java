package com.datn.questionbank.service;

import com.datn.questionbank.dto.ImportDto.*;
import com.datn.questionbank.entity.Question.*;
import com.datn.questionbank.entity.QuestionImportItem.SourceMethod;
import com.datn.questionbank.entity.QuestionImportJob.*;
import com.datn.questionbank.repository.*;
import com.datn.questionbank.security.QuestionActor;
import org.apache.pdfbox.pdmodel.*;
import org.apache.pdfbox.pdmodel.font.*;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.poi.xwpf.usermodel.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"eureka.client.enabled=false","spring.cloud.discovery.enabled=false",
        "jwt.secret=test-only-jwt-key-32-bytes-or-longer-never-production",
        "question-bank.internal-token=isolated-test-service-token"})
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named="QB_TEST_DATABASE",matches="true")
@Transactional
class QuestionImportPersistenceTest {
    @Autowired QuestionImportService imports;
    @Autowired QuestionService questions;
    @Autowired QuestionRepository questionRepo;
    @Autowired CategoryRepository categories;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired MockMvc http;
    @MockBean AuthIdentityClient identities;
    QuestionActor owner=new QuestionActor(UUID.randomUUID(),"TEACHER",false);
    QuestionActor other=new QuestionActor(UUID.randomUUID(),"TEACHER",false);
    UUID category;
    @BeforeEach void setup(){category=categories.findAll().getFirst().getId();}
    int fails(Runnable call){return assertThrows(ResponseStatusException.class,call::run).getStatusCode().value();}

    static final String TEMPLATE="""
            Câu 1: Thủ đô của Việt Nam là?
            A. Huế
            B. Hà Nội
            Đáp án: B
            Giải thích: Từ năm 1010.
            Câu 2: Số nguyên tố nhỏ nhất?
            A. 1
            B. 2
            C. 3
            """;
    static byte[] docx(String text,boolean pageBreakBeforeSecond) throws IOException {
        try(var doc=new XWPFDocument();var out=new ByteArrayOutputStream()){
            for(String line:text.strip().split("\n")){var p=doc.createParagraph();var run=p.createRun();
                if(pageBreakBeforeSecond&&line.startsWith("Câu 2"))run.addBreak(BreakType.PAGE);run.setText(line);}
            doc.write(out);return out.toByteArray();}
    }
    static byte[] pdf(int pages,String... pageText) throws IOException {
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){
            for(int i=0;i<pages;i++){var page=new PDPage();doc.addPage(page);
                if(i<pageText.length&&pageText[i]!=null)try(var cs=new PDPageContentStream(doc,page)){
                    cs.beginText();cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA),12);cs.setLeading(16);cs.newLineAtOffset(50,700);
                    for(String line:pageText[i].strip().split("\n")){cs.showText(line);cs.newLine();}cs.endText();}}
            doc.save(out);return out.toByteArray();}
    }
    static BufferedImage picture(String text){
        var image=new BufferedImage(1700,900,BufferedImage.TYPE_INT_RGB);var g=image.createGraphics();
        g.setColor(Color.WHITE);g.fillRect(0,0,1700,900);g.setColor(Color.BLACK);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setFont(new Font("Arial",Font.PLAIN,44));int y=80;for(String line:text.strip().split("\n")){g.drawString(line,60,y);y+=70;}
        g.dispose();return image;
    }
    static byte[] scannedPdf(String text) throws IOException {
        try(var doc=new PDDocument();var out=new ByteArrayOutputStream()){var page=new PDPage();doc.addPage(page);
            var image=LosslessFactory.createFromImage(doc,picture(text));
            try(var cs=new PDPageContentStream(doc,page)){cs.drawImage(image,0,300,612,324);}
            doc.save(out);return out.toByteArray();}
    }
    ImportJobResponse run(String name,byte[] bytes,UUID categoryId){
        var job=imports.upload(new MockMultipartFile("file",name,"application/octet-stream",bytes),categoryId,null,owner);
        assertEquals(Status.PROCESSING,job.status());
        imports.process(job.id(),bytes,owner.userId());
        return imports.get(job.id(),owner);
    }
    List<ImportItemResponse> items(UUID job,ItemFilter filter){return imports.items(job,filter,PageRequest.of(0,100),owner).getContent();}
    static boolean tesseract(){try{return new ProcessBuilder("tesseract","--version").start().waitFor()==0;}catch(Exception e){return false;}}

    @Test void docxReviewEditValidateAndImportAsPersonalDraftWithAudit() throws Exception {
        var job=run("de-kiem-tra.docx",docx(TEMPLATE,true),category);
        assertEquals(Status.READY,job.status());assertEquals(FileKind.DOCX,job.fileKind());assertEquals(2,job.pageCount());
        assertEquals(new ImportSummary(2,1,1,0,1,0,0),job.summary());
        var all=items(job.id(),ItemFilter.ALL);
        var first=all.get(0);var second=all.get(1);
        assertEquals(new SourceRef("de-kiem-tra.docx",1,SourceMethod.TEXT),first.source());assertEquals(2,second.source().page());
        assertTrue(first.importable());assertEquals("Từ năm 1010.",first.explanation());
        assertFalse(second.valid());assertEquals("MISSING_CORRECT_ANSWER",second.errors().getFirst().code());
        assertTrue(second.flags().contains("MISSING_CORRECT_ANSWER"));
        assertEquals(List.of(second.id()),items(job.id(),ItemFilter.INVALID).stream().map(ImportItemResponse::id).toList());

        assertEquals(404,fails(()->imports.get(job.id(),other)));
        assertEquals(404,fails(()->imports.update(job.id(),second.id(),new ItemUpdate(second.version(),null,null,null,null,null,null,null,true,null),other)));
        assertEquals(409,fails(()->imports.update(job.id(),second.id(),new ItemUpdate(second.version()+1,null,null,null,null,null,null,null,true,null),owner)));
        var fixed=imports.update(job.id(),second.id(),new ItemUpdate(second.version(),null,null,DifficultyLevel.HARD,null,
                List.of(new ImportOption("1",false,(short)0),new ImportOption("2",true,(short)1),new ImportOption("3",false,(short)2)),null,"2 chỉ chia hết cho 1 và 2",null,null),owner);
        assertTrue(fixed.valid());assertTrue(fixed.importable());assertEquals(second.version()+1,fixed.version());
        assertEquals(new ImportSummary(2,2,0,0,2,0,0),imports.validateAll(job.id(),owner));

        var result=imports.importItems(job.id(),null,owner);
        assertEquals(2,result.imported().size());assertTrue(result.skipped().isEmpty());
        var created=questionRepo.findById(result.imported().get(1).questionId()).orElseThrow();
        assertEquals(QuestionStatus.DRAFT,created.getStatus());assertEquals(CreationMethod.IMPORT,created.getCreationMethod());
        assertEquals(QuestionScope.PERSONAL,created.getScope());assertEquals(SourceType.USER_DOCUMENT,created.getSourceType());
        assertEquals("de-kiem-tra.docx · trang 2",created.getSourceLabel());assertEquals("import:"+job.id()+":"+second.id(),created.getSourceRefId());
        assertEquals(DifficultyLevel.HARD,created.getDifficultyLevel());assertEquals("2 chỉ chia hết cho 1 và 2",created.getExplanation());
        assertEquals(owner.userId(),created.getCreatedBy());
        var again=imports.importItems(job.id(),new ImportRequest(List.of(second.id(),UUID.randomUUID()),null),owner);
        assertEquals(List.of("ALREADY_IMPORTED","NOT_IN_JOB"),again.skipped().stream().map(SkippedItem::reason).toList());
        assertEquals(409,fails(()->imports.update(job.id(),second.id(),new ItemUpdate(fixed.version()+1,"x",null,null,null,null,null,null,null,null),owner)));
        var actions=jdbc.queryForList("SELECT action FROM question_import_events WHERE job_id=:job ORDER BY id",new MapSqlParameterSource("job",job.id()),String.class);
        assertEquals(List.of("UPLOADED","EXTRACTED","ITEM_UPDATED","VALIDATED","IMPORTED","IMPORTED"),actions);
        imports.discard(job.id(),owner);
        assertEquals(409,fails(()->imports.validateAll(job.id(),owner)));
        assertTrue(questionRepo.findById(created.getId()).isPresent());
    }

    @Test void flaggedItemsNeedExplicitReviewAndMissingCategoryBlocksImport() throws Exception {
        var job=run("en.pdf",pdf(1,"""
                Question 1: Pick one
                A. Red
                C. Blue
                Answer: A
                """),null);
        assertEquals(FileKind.PDF,job.fileKind());assertEquals(0,job.ocrPageCount());
        var item=items(job.id(),ItemFilter.ALL).getFirst();
        assertTrue(item.reviewRequired());assertTrue(item.flags().contains("OPTION_SEQUENCE_GAP"));
        assertEquals("CATEGORY_REQUIRED",item.errors().getFirst().code());
        assertEquals(List.of("INVALID"),imports.importItems(job.id(),null,owner).skipped().stream().map(SkippedItem::reason).toList());
        var withCategory=imports.update(job.id(),item.id(),new ItemUpdate(item.version(),null,null,null,category,null,null,null,null,null),owner);
        assertTrue(withCategory.valid());assertFalse(withCategory.importable());
        assertEquals(List.of(item.id()),items(job.id(),ItemFilter.NEEDS_REVIEW).stream().map(ImportItemResponse::id).toList());
        assertEquals(List.of("NEEDS_REVIEW"),imports.importItems(job.id(),null,owner).skipped().stream().map(SkippedItem::reason).toList());
        imports.update(job.id(),item.id(),new ItemUpdate(withCategory.version(),null,null,null,null,null,null,null,true,null),owner);
        assertEquals(1,imports.importItems(job.id(),null,owner).imported().size());
    }

    @Test void limitsAndUnsupportedFilesFailClearly() throws Exception {
        assertEquals(400,fails(()->imports.upload(new MockMultipartFile("file","a.txt","text/plain","Câu 1: x".getBytes()),null,null,owner)));
        assertEquals(400,fails(()->imports.upload(new MockMultipartFile("file","a.docx","application/octet-stream",new byte[0]),null,null,owner)));
        var onePage=pdf(1,"x");
        assertEquals(403,fails(()->imports.upload(new MockMultipartFile("file","a.pdf","application/pdf",onePage),null,null,new QuestionActor(UUID.randomUUID(),"STUDENT",false))));
        var tooLong=run("long.pdf",pdf(31,"Question 1: x"),null);
        assertEquals(Status.FAILED,tooLong.status());assertEquals("Tài liệu vượt 30 trang",tooLong.failureReason());
        var noTemplate=run("plain.pdf",pdf(1,"Just some notes without the template lines here"),null);
        assertEquals(Status.FAILED,noTemplate.status());assertTrue(noTemplate.failureReason().startsWith("Không nhận diện"));
        assertEquals(409,fails(()->imports.importItems(noTemplate.id(),null,owner)));
        http.perform(get("/question-imports").header("X-Service-Token","isolated-test-service-token").header("X-Question-Owner-Id",owner.userId()))
                .andExpect(status().isForbidden());
    }

    String jwt(QuestionActor actor){
        return "Bearer "+io.jsonwebtoken.Jwts.builder().subject(actor.userId().toString()).claim("type","access").claim("role",actor.role())
                .expiration(new Date(System.currentTimeMillis()+60000)).signWith(io.jsonwebtoken.security.Keys.hmacShaKeyFor(
                        "test-only-jwt-key-32-bytes-or-longer-never-production".getBytes(java.nio.charset.StandardCharsets.UTF_8))).compact();
    }
    @Test @Transactional(propagation=org.springframework.transaction.annotation.Propagation.NOT_SUPPORTED)
    void httpUploadExtractsAsynchronouslyAfterCommitThenImports() throws Exception {
        var body=http.perform(multipart("/question-imports").file(new MockMultipartFile("file","async.docx","application/octet-stream",docx(TEMPLATE,false)))
                .param("categoryId",category.toString()).param("defaultDifficulty","EASY").header("Authorization",jwt(owner)))
                .andExpect(status().isAccepted()).andExpect(jsonPath("$.status").value("PROCESSING")).andReturn().getResponse().getContentAsString();
        String id=new com.fasterxml.jackson.databind.ObjectMapper().readTree(body).get("id").asText();
        String state="PROCESSING";
        for(int i=0;i<100&&state.equals("PROCESSING");i++){Thread.sleep(100);
            state=new com.fasterxml.jackson.databind.ObjectMapper().readTree(http.perform(get("/question-imports/"+id).header("Authorization",jwt(owner)))
                    .andReturn().getResponse().getContentAsString()).get("status").asText();}
        assertEquals("READY",state);
        http.perform(get("/question-imports/"+id).header("Authorization",jwt(other))).andExpect(status().isNotFound());
        http.perform(get("/question-imports/"+id+"/items").param("filter","IMPORTABLE").header("Authorization",jwt(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.totalElements").value(1)).andExpect(jsonPath("$.content[0].difficultyLevel").value("EASY"))
                .andExpect(jsonPath("$.content[0].options[1].correct").value(true)).andExpect(jsonPath("$.content[0].source.fileName").value("async.docx"));
        http.perform(post("/question-imports/"+id+"/import").header("Authorization",jwt(owner)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.imported.length()").value(1)).andExpect(jsonPath("$.skipped[0].reason").value("INVALID"));
        http.perform(get("/question-imports").header("Authorization",jwt(owner))).andExpect(status().isOk()).andExpect(jsonPath("$.content[0].summary.imported").value(1));
    }
    @Test void scannedPdfPagesAreOcrdWithVietnameseAndAlwaysNeedReview() throws Exception {
        Assumptions.assumeTrue(tesseract(),"tesseract CLI không có trên máy test");
        var job=run("scan.pdf",scannedPdf("""
                Câu 1: Thủ đô của Việt Nam là?
                A. Huế
                B. Hà Nội
                Đáp án: B
                """),category);
        assertEquals(Status.READY,job.status(),job.failureReason()+" "+job.warnings());assertEquals(1,job.ocrPageCount());
        var item=items(job.id(),ItemFilter.ALL).getFirst();
        assertEquals(SourceMethod.OCR,item.source().method());assertTrue(item.flags().contains("OCR"));assertTrue(item.reviewRequired());
        assertTrue(item.confidence().doubleValue()>0&&item.confidence().doubleValue()<=1);
        assertTrue(item.content().contains("Việt Nam"),item.content());
        assertEquals(2,item.options().size());assertTrue(item.options().get(1).correct(),item.rawText());
        var image=new ByteArrayOutputStream();javax.imageio.ImageIO.write(picture("Câu 1: Hai cộng hai bằng mấy?\nĐáp án: 4"),"png",image);
        var png=run("anh.png",image.toByteArray(),category);
        assertEquals(FileKind.IMAGE,png.fileKind());
        var shortAnswer=items(png.id(),ItemFilter.ALL).getFirst();
        assertEquals(QuestionType.SHORT_ANSWER,shortAnswer.questionType());assertEquals("4",shortAnswer.expectedAnswer());
    }
}
