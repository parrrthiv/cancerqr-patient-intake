-- =============================================================================
-- V4: prepare doctor passwords for Spring Security's DelegatingPasswordEncoder.
--
-- Up to V3, doctor.password was stored as plaintext and compared with String.equals().
-- From this release onward, login goes through Spring Security with a
-- DelegatingPasswordEncoder. New writes use {bcrypt}<hash>; legacy rows here
-- are rewritten to {noop}<plaintext> so they still verify on the next login.
--
-- On the first successful login of each legacy account the encoding is upgraded
-- to {bcrypt} automatically (see DoctorUserDetailsService.updatePassword).
-- =============================================================================
UPDATE doctors
SET password = '{noop}' || password
WHERE password NOT LIKE '{%}%';
