package com.project.secretdiary.service;

import com.project.secretdiary.dto.request.FindMemberRequest;
import com.project.secretdiary.dto.request.MailRequest;
import com.project.secretdiary.dto.request.SignInRequest;
import com.project.secretdiary.dto.request.TokenRequest;
import com.project.secretdiary.entity.MemberEntity;
import com.project.secretdiary.exception.CustomJwtException;
import com.project.secretdiary.exception.PasswordNotMatchException;
import com.project.secretdiary.exception.UserNotFoundException;
import com.project.secretdiary.jwt.JwtTokenProvider;
import com.project.secretdiary.dto.response.TokenResponse;
import com.project.secretdiary.repository.MemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.ObjectUtils;

import java.util.concurrent.TimeUnit;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SignService {
    private final MemberRepository memberRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;
    private final RedisTemplate redisTemplate;
    private final MailService mailService;
    private static final int PASSWORD_LENGTH = 10;

    public TokenResponse signIn(final SignInRequest signInRequest) {

        MemberEntity member = memberRepository.findByUserId(signInRequest.getUserId())
                .orElseThrow(() -> new UserNotFoundException());

        if(!passwordEncoder.matches(signInRequest.getPassword(), member.getPassword())) {
            throw new PasswordNotMatchException();
        }

        TokenResponse tokenResponse = jwtTokenProvider.createToken(String.valueOf(member.getId()));

        redisTemplate.opsForValue()
                .set(member.getUserId(),
                        tokenResponse.getRefreshToken(),
                        tokenResponse.getReExpireTime(), TimeUnit.MILLISECONDS);

        return tokenResponse;
    }

    @Transactional
    public void findPassword(final FindMemberRequest findMemberRequest) {
        MemberEntity member = memberRepository.findByUserId(findMemberRequest.getUserId())
                .orElseThrow(() -> new UserNotFoundException());

        if(!member.isSameEmail(findMemberRequest.getEmail())) {
            throw new UserNotFoundException("???????????? ???????????? ????????????.");
        }

        String tmpPassword = createPassword();
        member.changePassword(passwordEncoder.encode(tmpPassword));

        MailRequest mailRequest = new MailRequest();
        mailRequest.setAddress(findMemberRequest.getEmail());
        mailRequest.setTitle("[?????? ?????? ?????? ?????????] ?????? ???????????? ?????? ???????????????.");
        mailRequest.setMessage("???????????????. [?????? ?????? ?????????] ?????? ?????? ??????????????? ?????? ????????????.\n" +
                findMemberRequest.getUserId() +" ?????? ?????? ??????????????? " + tmpPassword + " ?????????." );

        mailService.sendMail(mailRequest);
    }

    public String createPassword() {
        char[] letters = new char[] {'0','1','2','3','4','5','6','7','8','9','A','B','C','D','E',
                'F','G','H','I','J','K','L','M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V', 'W', 'X', 'Y', 'Z'};
        String tmpPassword="";
        int index = 0;
        for(int i = 0; i < PASSWORD_LENGTH; i++) {
            index = (int) (letters.length * Math.random());
            tmpPassword += letters[index];
        }
        return tmpPassword;
    }

    public String findUserId(final String email) {
        MemberEntity member = memberRepository.findByEmail(email)
                .orElseThrow(() -> new UserNotFoundException());
        String userId = member.getUserId();
        return userId;
    }

    public TokenResponse reissue(final TokenRequest tokenRequest) {
        if(!jwtTokenProvider.validateToken(tokenRequest.getRefreshToken())) {
            throw new CustomJwtException("???????????? ?????? refresh token ?????????.");
        }
        Authentication authentication = jwtTokenProvider.getAuthentication(tokenRequest.getAccessToken());
        String refreshToken = (String)redisTemplate.opsForValue().get(authentication.getName());

        if(ObjectUtils.isEmpty(refreshToken)) {
            throw new CustomJwtException("????????? ???????????????.");
        }
        if(!refreshToken.equals(tokenRequest.getRefreshToken())) {
            throw new CustomJwtException("refresh token ????????? ???????????? ????????????.");
        }
        MemberEntity member = memberRepository.findByUserId(authentication.getName())
                .orElseThrow(() -> new UserNotFoundException());

        TokenResponse tokenResponse = jwtTokenProvider.createToken(String.valueOf(member.getId()));

        redisTemplate.opsForValue()
                .set(member.getUserId(),
                        tokenResponse.getRefreshToken(),
                        tokenResponse.getReExpireTime(), TimeUnit.MILLISECONDS);
        return tokenResponse;
    }
    public void signOut(final TokenRequest tokenRequest) {
        if(!jwtTokenProvider.validateToken(tokenRequest.getAccessToken())) {
            throw new CustomJwtException("???????????? ?????? access token ?????????.");
        }
        Authentication authentication = jwtTokenProvider.getAuthentication(tokenRequest.getAccessToken());

        if(redisTemplate.opsForValue().get(authentication.getName()) != null) {
            redisTemplate.delete(authentication.getName());
        }

        Long expiration = jwtTokenProvider.getExpiration(tokenRequest.getAccessToken());
        redisTemplate.opsForValue()
                .set(tokenRequest.getAccessToken(), "logout", expiration, TimeUnit.MILLISECONDS);
    }
}